package com.example.myplan.focus

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.CreditEntry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.*

class CreditRepository(private val db: PlanDatabase) {
    private val date = MutableStateFlow(LocalDate.now().toString())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val balance: Flow<Int> = date.flatMapLatest { db.creditDao().observeBalance(it) }
        .map { it.coerceAtLeast(0) }

    fun refreshDate() { date.value = LocalDate.now().toString() }

    suspend fun awardTask(id: Long, count: Int, today: String = LocalDate.now().toString()): Boolean = db.withTransaction {
        require(count > 0)
        db.creditDao().putOnce(CreditEntry("task:$id:$today", today, count)) != -1L
    }

    suspend fun consume(today: String = LocalDate.now().toString(), appPackage: String? = null): Boolean = db.withTransaction {
        val key = if (appPackage != null) "unlock:$appPackage:$today" else "spend:${UUID.randomUUID()}"
        if (db.creditDao().get(key) != null) return@withTransaction true
        if (db.creditDao().balance(today) <= 0) return@withTransaction false
        db.creditDao().put(CreditEntry(key, today, -1))
        true
    }
}
