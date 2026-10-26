package com.example.myplan.focus

import android.content.Context
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.session.AndroidFocusClock
import com.example.myplan.focus.session.FocusClock
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.session.FocusSessionRepository
import com.example.myplan.focus.session.TimedUnlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.LocalDate
import java.time.ZoneId

enum class FocusLockReason { SESSION, DAILY_LIMIT }

data class FocusEnforcementSnapshot(
    val lockedPackages: Set<String>,
    val sessionPackages: Set<String>,
    val timedUnlockedPackages: Set<String>,
    val nextCheckInMillis: Long?
) {
    fun reason(packageName: String): FocusLockReason? = when (packageName) {
        in sessionPackages -> FocusLockReason.SESSION
        in lockedPackages -> FocusLockReason.DAILY_LIMIT
        else -> null
    }
}

/** Combines persisted rules and live entitlements; no lock decision relies on a cached grant. */
class FocusEnforcement(context: Context, private val clock: FocusClock = AndroidFocusClock(context)) {
    private val app = context.applicationContext as MyPlanApp
    private val planDb = PlanDatabase.getInstance(app)
    private val sessions = FocusSessionRepository(planDb, clock)
    private val unlocks = TimedUnlockRepository(planDb, clock)
    private val usage = AppUsageRepository(app, app.focusDb)
    private val limits = LimitRepository(app.focusDb)

    suspend fun snapshot(): FocusEnforcementSnapshot {
        val session = sessions.reconcile()
        val leases = unlocks.observeUnlocks().first()
        val now = clock.now()
        val sessionRemaining = session?.takeIf { it.status == FocusSessionEntity.RUNNING }
            ?.remainingAt(now)?.takeIf { it > 0 }
        val sessionPackages = FocusAppSafety.filterPackages(app,
            if (sessionRemaining != null) session!!.packages else emptySet())
        val activeLeases = leases.filter { it.remainingAt(now) > 0 }
        val timedPackages = activeLeases.map { it.packageName }.toSet()
        val rows = app.focusDb.usageLogDao().getUsageForDateSync(now.date)
        val rules = limits.getAllAppLimitsSync() + limits.getCategoryLimitsSync()
        val quotaLocks = FocusLimitPolicy.lockedPackages(rows, rules)
        val effectiveLocks = FocusLimitPolicy.effectiveLockedPackages(quotaLocks, sessionPackages, timedPackages)
        val midnightRemaining = LocalDate.parse(now.date).plusDays(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() - now.wallTimeMillis
        val boundaries = activeLeases.map { it.remainingAt(now) } + listOfNotNull(sessionRemaining) +
            listOfNotNull(midnightRemaining.takeIf { it > 0 })
        return FocusEnforcementSnapshot(
            FocusAppSafety.filterPackages(app, effectiveLocks), sessionPackages, timedPackages,
            boundaries.minOrNull()
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeChanges(): Flow<Unit> = merge(
        app.dates.today.flatMapLatest { usage.getUsageForDate(it.toString()) }.map { Unit },
        limits.getAllLimits().map { Unit },
        planDb.planDao().observeAllPlans().map { Unit },
        planDb.planStepDao().observeAll().map { Unit },
        sessions.observeSession().map { Unit },
        unlocks.observeUnlocks().map { Unit }
    )
}
