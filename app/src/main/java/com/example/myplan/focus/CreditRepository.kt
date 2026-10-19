package com.example.myplan.focus

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.CreditEntry
import java.util.UUID
import kotlinx.coroutines.flow.*
import com.example.myplan.data.DateProvider

class CreditRepository(private val db: PlanDatabase, private val dates: DateProvider = DateProvider()) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val balance: Flow<Int> = dates.today.flatMapLatest { db.creditDao().observeBalance(it.toString()) }
        .map { it.coerceAtLeast(0) }

    fun refreshDate() { dates.refresh() }

    suspend fun awardTask(id: Long, count: Int, today: String = dates.currentDate().toString()): Boolean = db.withTransaction {
        require(count > 0)
        db.creditDao().putOnce(CreditEntry("task:$id:$today", today, count)) != -1L
    }

    suspend fun consume(today: String = dates.currentDate().toString(), appPackage: String? = null): Boolean = db.withTransaction {
        val key = if (appPackage != null) "unlock:$appPackage:$today" else "spend:${UUID.randomUUID()}"
        if (db.creditDao().get(key) != null) return@withTransaction true
        if (db.creditDao().balance(today) <= 0) return@withTransaction false
        db.creditDao().put(CreditEntry(key, today, -1))
        true
    }
}
