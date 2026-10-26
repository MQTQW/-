package com.example.myplan.focus.session

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.CreditEntry

sealed interface TimedUnlockResult {
    data class Granted(val lease: TimedUnlockEntity, val reused: Boolean) : TimedUnlockResult
    data object InsufficientCredits : TimedUnlockResult
    data object BlockedByFocus : TimedUnlockResult
}

class TimedUnlockRepository(private val db: PlanDatabase, private val clock: FocusClock) {
    private val dao = db.focusSessionDao()
    fun observeUnlocks() = dao.observeUnlocks()

    suspend fun activePackages(): Set<String> {
        val time = clock.now()
        return dao.allUnlocks().filter { it.remainingAt(time) > 0 }.map { it.packageName }.toSet()
    }

    suspend fun remaining(packageName: String): Long {
        val time = clock.now()
        return dao.allUnlocks().filter { it.packageName == packageName }.maxOfOrNull { it.remainingAt(time) } ?: 0L
    }

    suspend fun redeem(packageName: String, requestId: String): TimedUnlockResult = db.withTransaction {
        require(FocusSessionRepository.validPackage(packageName)) { "请选择有效应用" }
        require(requestId.isNotBlank() && requestId.length <= 255) { "缺少兑换请求标识" }
        val time = clock.now()
        val session = FocusSessionRepository(db, clock).reconcileLocked(time)
        if (session?.status == FocusSessionEntity.RUNNING && packageName in session.packages)
            return@withTransaction TimedUnlockResult.BlockedByFocus
        dao.unlock(requestId)?.let {
            require(it.packageName == packageName) { "兑换请求与应用不一致" }
            return@withTransaction TimedUnlockResult.Granted(it, reused = true)
        }
        dao.allUnlocks().firstOrNull { it.packageName == packageName && it.remainingAt(time) > 0 }?.let {
            // Remember this request too: retrying it after expiry must not issue a new grant.
            val reused = it.copy(requestId = requestId, cost = 0)
            dao.insertUnlock(reused)
            return@withTransaction TimedUnlockResult.Granted(reused, reused = true)
        }
        if (db.creditDao().balance(time.date) < COST) return@withTransaction TimedUnlockResult.InsufficientCredits
        val lease = TimedUnlockEntity(requestId, packageName, time.date, DURATION_MILLIS, COST,
            time.wallTimeMillis, time.wallTimeMillis + DURATION_MILLIS, time.elapsedRealtimeMillis,
            time.elapsedRealtimeMillis + DURATION_MILLIS, time.bootCount)
        db.creditDao().put(CreditEntry("timed-unlock:$requestId", time.date, -COST))
        dao.insertUnlock(lease)
        TimedUnlockResult.Granted(lease, reused = false)
    }

    companion object {
        const val COST = 1
        const val DURATION_MINUTES = 10
        const val DURATION_MILLIS = 600_000L
    }
}
