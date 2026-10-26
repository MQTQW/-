package com.example.myplan.focus

import androidx.test.core.app.ApplicationProvider
import com.example.myplan.MyPlanApp
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.resetAppDatabases
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.session.FocusClock
import com.example.myplan.focus.session.FocusSessionRepository
import com.example.myplan.focus.session.FocusTime
import com.example.myplan.focus.session.TimedUnlockEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = MyPlanApp::class, sdk = [29])
class FocusEnforcementTest {
    @Before fun reset() { resetAppDatabases() }

    private class TestClock : FocusClock {
        var time = FocusTime(System.currentTimeMillis(), 100_000L, 1, LocalDate.now().toString())
        override fun now() = time
        fun advance(millis: Long) {
            time = time.copy(wallTimeMillis = time.wallTimeMillis + millis,
                elapsedRealtimeMillis = time.elapsedRealtimeMillis + millis)
        }
    }

    private suspend fun app(): MyPlanApp {
        val app = ApplicationProvider.getApplicationContext<MyPlanApp>()
        DailyRollover.ensureToday(app)
        withContext(Dispatchers.IO) {
            PlanDatabase.getInstance(app).clearAllTables()
            app.focusDb.clearAllTables()
        }
        return app
    }

    private suspend fun quota(app: MyPlanApp, clock: TestClock, legacyUnlocked: Boolean = false): Long {
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "video.app", appName = "视频",
            date = clock.now().date, totalTimeMillis = 1000L, isUnlocked = legacyUnlocked))
        return LimitRepository(app.focusDb).insertLimit(AppLimitEntity(packageName = "video.app", dailyLimitMillis = 100L))
    }

    private suspend fun lease(app: MyPlanApp, clock: TestClock) {
        val time = clock.now()
        PlanDatabase.getInstance(app).focusSessionDao().insertUnlock(TimedUnlockEntity(
            "lease", "video.app", time.date, 600_000L, 1, time.wallTimeMillis,
            time.wallTimeMillis + 600_000L, time.elapsedRealtimeMillis,
            time.elapsedRealtimeMillis + 600_000L, time.bootCount))
    }

    @Test fun expiredLeaseRestoresCurrentQuotaWithoutAnyMonitorTick() = runBlocking {
        val app = app(); val clock = TestClock(); quota(app, clock); lease(app, clock)
        assertNull(FocusEnforcement(app, clock).snapshot().reason("video.app"))
        clock.advance(600_001)
        // A new evaluator represents process restart: it cannot remember the expired grant.
        assertEquals(FocusLockReason.DAILY_LIMIT, FocusEnforcement(app, clock).snapshot().reason("video.app"))
        assertFalse(app.focusDb.usageLogDao().getUsageForPackageAndDate("video.app", clock.now().date)!!.isUnlocked)
    }

    @Test fun editingQuotaDuringLeaseDoesNotRestoreAnObsoleteRule() = runBlocking {
        val app = app(); val clock = TestClock(); val rule = quota(app, clock); lease(app, clock)
        LimitRepository(app.focusDb).setLimitEnabled(rule, false)
        clock.advance(600_001)
        assertNull(FocusEnforcement(app, clock).snapshot().reason("video.app"))
    }

    @Test fun runningSessionOverridesBothEntitlementsAndPauseKeepsQuotaRules() = runBlocking {
        val app = app(); val clock = TestClock(); quota(app, clock, legacyUnlocked = true); lease(app, clock)
        val db = PlanDatabase.getInstance(app)
        val planId = db.planDao().insertPlan(PlanEntity(name = "阅读"))
        val sessions = FocusSessionRepository(db, clock)
        val session = sessions.start(planId, 25, setOf("video.app", "other.app"))
        val running = FocusEnforcement(app, clock).snapshot()
        assertEquals(FocusLockReason.SESSION, running.reason("video.app"))
        assertEquals(FocusLockReason.SESSION, running.reason("other.app"))
        // Session-only blocking is never persisted as a daily quota row.
        assertNull(app.focusDb.usageLogDao().getUsageForPackageAndDate("other.app", clock.now().date))
        sessions.pause(session.sessionId)
        assertTrue(FocusEnforcement(app, clock).snapshot().lockedPackages.isEmpty())
        clock.advance(600_001)
        assertNull(FocusEnforcement(app, clock).snapshot().reason("video.app"))
    }

    @Test fun pauseReleasesSessionOnlyBlockButNotExpiredQuota() = runBlocking {
        val app = app(); val clock = TestClock(); quota(app, clock)
        val db = PlanDatabase.getInstance(app)
        val planId = db.planDao().insertPlan(PlanEntity(name = "阅读"))
        val sessions = FocusSessionRepository(db, clock)
        val session = sessions.start(planId, 25, setOf("video.app", "other.app"))
        sessions.pause(session.sessionId)
        val paused = FocusEnforcement(app, clock).snapshot()
        assertEquals(FocusLockReason.DAILY_LIMIT, paused.reason("video.app"))
        assertNull(paused.reason("other.app"))
    }

    @Test fun safetyAlwaysKeepsOwnAppAndSystemControlsAccessible() = runBlocking {
        val app = app()
        assertEquals(setOf("video.app"), FocusAppSafety.filterPackages(app,
            listOf(app.packageName, "android", "com.android.settings", "com.android.systemui", "video.app")))
    }
}
