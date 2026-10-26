package com.example.myplan.focus.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

data class FocusTime(
    val wallTimeMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootCount: Int,
    val date: String
)

fun interface FocusClock { fun now(): FocusTime }

class AndroidFocusClock(context: Context) : FocusClock {
    private val resolver = context.applicationContext.contentResolver
    override fun now() = FocusTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0), LocalDate.now().toString())
}

/** A single current session survives process death; completion rewards belong to PlanActions. */
@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: Int = 1,
    val sessionId: String,
    val planId: Long,
    val planName: String,
    val planUpdatedAt: Long,
    val date: String,
    val status: String,
    val durationMillis: Long,
    val remainingMillis: Long,
    val blockedPackages: String,
    val startedAt: Long,
    val updatedAt: Long,
    val anchorElapsedRealtime: Long,
    val bootCount: Int,
    val stepId: Long? = null,
    val stepTitle: String? = null
) {
    val packages: Set<String> get() = blockedPackages.lineSequence().filter { it.isNotBlank() }.toSet()
    val isUnfinished: Boolean get() = status in setOf(RUNNING, PAUSED, READY)

    fun remainingAt(time: FocusTime): Long = when {
        !isUnfinished || status == READY || time.date != date -> 0L
        status != RUNNING || time.bootCount != bootCount -> remainingMillis
        else -> (remainingMillis - (time.elapsedRealtimeMillis - anchorElapsedRealtime).coerceAtLeast(0)).coerceAtLeast(0)
    }

    companion object {
        const val RUNNING = "RUNNING"
        const val PAUSED = "PAUSED"
        const val READY = "READY"
        const val COMPLETED = "COMPLETED"
        const val CANCELLED = "CANCELLED"
    }
}

@Entity(tableName = "timed_unlocks")
data class TimedUnlockEntity(
    @PrimaryKey val requestId: String,
    val packageName: String,
    val date: String,
    val durationMillis: Long,
    val cost: Int,
    val startedAt: Long,
    val expiresAt: Long,
    val startedElapsedRealtime: Long,
    val expiresElapsedRealtime: Long,
    val bootCount: Int
) {
    /** Wall-clock changes cannot extend an already issued grant. Reboot/day change revokes it. */
    fun remainingAt(time: FocusTime): Long {
        if (time.date != date || time.bootCount != bootCount || time.elapsedRealtimeMillis < startedElapsedRealtime) return 0
        return minOf(expiresAt - time.wallTimeMillis, expiresElapsedRealtime - time.elapsedRealtimeMillis,
            durationMillis).coerceAtLeast(0)
    }
}
