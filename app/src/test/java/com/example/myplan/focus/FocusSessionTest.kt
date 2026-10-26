package com.example.myplan.focus

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.CreditEntry
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.session.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class FocusSessionTest {
    private lateinit var db: PlanDatabase
    private lateinit var plans: PlanRepository
    private lateinit var sessions: FocusSessionRepository
    private lateinit var unlocks: TimedUnlockRepository
    private val clock = TestClock()
    private val day get() = clock.time.date

    private class TestClock : FocusClock {
        var time = FocusTime(1_792_987_200_000, 60_000, 10, "2026-10-26")
        override fun now() = time
        fun advance(millis: Long) { time = time.copy(wallTimeMillis = time.wallTimeMillis + millis,
            elapsedRealtimeMillis = time.elapsedRealtimeMillis + millis) }
    }

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanRepository(db)
        sessions = FocusSessionRepository(db, clock)
        unlocks = TimedUnlockRepository(db, clock)
    }
    @After fun close() { db.close() }
    private suspend fun plan() = plans.insertPlan(PlanEntity(name = "阅读", updatedAt = 123))
    private suspend fun credit(count: Int = 1) { db.creditDao().put(CreditEntry("test", day, count)) }

    @Test fun reopeningDatabaseRestoresSessionAndTimedGrant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "focus-restore-${java.util.UUID.randomUUID()}"
        var persistent = Room.databaseBuilder(context, PlanDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val id = persistent.planDao().insertPlan(PlanEntity(name = "持久化计划", updatedAt = 123))
            persistent.creditDao().put(CreditEntry("test", day, 1))
            FocusSessionRepository(persistent, clock).start(id, 25, setOf("video.a"), "session")
            TimedUnlockRepository(persistent, clock).redeem("video.other", "grant")
            persistent.close()
            clock.advance(120_000)
            persistent = Room.databaseBuilder(context, PlanDatabase::class.java, name).allowMainThreadQueries().build()
            val restored = FocusSessionRepository(persistent, clock).current()!!
            assertEquals("session", restored.sessionId)
            assertEquals(1_380_000L, restored.remainingAt(clock.now()))
            assertEquals(480_000L, TimedUnlockRepository(persistent, clock).remaining("video.other"))
            assertEquals(0, persistent.creditDao().balance(day))
        } finally { persistent.close(); context.deleteDatabase(name) }
    }

    @Test fun processRestartRestoresMonotonicRemainingAndPauseExcludesBreak() = runBlocking {
        val session = sessions.start(plan(), 25, setOf("tv.danmaku.bili"), "start")
        clock.advance(70_000)
        val restored = FocusSessionRepository(db, clock).current()!!
        assertEquals(session.sessionId, restored.sessionId)
        assertEquals(1_430_000L, restored.remainingAt(clock.now()))
        assertEquals(session, db.focusSessionDao().current()) // Reading never writes a ticking row.
        val paused = sessions.pause(session.sessionId)!!
        clock.advance(300_000)
        assertEquals(1_430_000L, paused.remainingAt(clock.now()))
        sessions.resume(session.sessionId)
        clock.advance(30_000)
        assertEquals(1_400_000L, sessions.current()!!.remainingAt(clock.now()))
    }

    @Test fun countdownRequiresExplicitCompletionAndCannotRewardTwice() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, emptySet(), "start")
        clock.advance(60_000)
        assertEquals(FocusSessionEntity.READY, sessions.current()!!.status)
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(day))
        val completed = sessions.confirmCompletion(session.sessionId) { planId, date -> plans.setCompleted(planId, true, date) }
        assertEquals(FocusSessionEntity.COMPLETED, completed!!.status)
        assertEquals(1, db.creditDao().balance(day))
        assertNull(sessions.confirmCompletion(session.sessionId) { _, _ -> fail("Already confirmed") })
        assertEquals(1, db.creditDao().balance(day))
    }

    @Test fun confirmationFailureRollsBackPlanRewardAndSessionTogether() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, emptySet(), "start")
        clock.advance(60_000)
        sessions.reconcile()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_finish BEFORE INSERT ON focus_sessions
            WHEN NEW.status = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END""")
        try {
            sessions.confirmCompletion(session.sessionId) { planId, date -> plans.setCompleted(planId, true, date) }
            fail("Expected injected write failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(day))
        assertEquals(FocusSessionEntity.READY, sessions.current()!!.status)
    }

    @Test fun editOrDeleteInvalidatesStaleSessionWithoutCompletingNewPlan() = runBlocking {
        val id = plan()
        val first = sessions.start(id, 1, emptySet(), "first")
        clock.advance(60_000)
        plans.updatePlan(plans.getPlanById(id)!!.copy(name = "已修改", updatedAt = 456))
        assertNull(sessions.confirmCompletion(first.sessionId) { _, _ -> fail("Edited plan must not finish") })
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        val next = sessions.start(id, 1, emptySet(), "next")
        plans.deletePlan(plans.getPlanById(id)!!)
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        assertNull(sessions.confirmCompletion(next.sessionId) { _, _ -> fail("Deleted plan must not finish") })
    }

    @Test fun externalCompletionCancelsSessionAndPreservesSingleReward() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, emptySet(), "start")
        plans.setCompleted(id, true, day)
        clock.advance(60_000)
        assertNull(sessions.confirmCompletion(session.sessionId) { _, _ -> fail("Already completed elsewhere") })
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        assertEquals(1, db.creditDao().balance(day))
    }

    @Test fun rebootPausesSessionAndExpiresUnlockInsteadOfInferringReward() = runBlocking {
        credit()
        unlocks.redeem("video.other", "grant")
        sessions.start(plan(), 25, setOf("video.a"), "start")
        clock.time = clock.time.copy(bootCount = 11, elapsedRealtimeMillis = 1_000)
        assertEquals(FocusSessionEntity.PAUSED, sessions.current()!!.status)
        assertEquals(0L, unlocks.remaining("video.other"))
        assertTrue(unlocks.activePackages().isEmpty())
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun newDayCancelsUnfinishedSessionAndDoesNotReuseYesterdayGrant() = runBlocking {
        credit()
        unlocks.redeem("video.a", "grant")
        sessions.start(plan(), 25, emptySet(), "start")
        clock.time = clock.time.copy(date = "2026-10-27")
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        assertEquals(0L, unlocks.remaining("video.a"))
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun changingWallClockCannotExtendCountdownOrUnlock() = runBlocking {
        credit()
        unlocks.redeem("video.a", "grant")
        sessions.start(plan(), 25, emptySet(), "start")
        clock.advance(60_000)
        clock.time = clock.time.copy(wallTimeMillis = clock.time.wallTimeMillis - 3_600_000)
        assertEquals(540_000L, unlocks.remaining("video.a"))
        assertEquals(1_440_000L, sessions.current()!!.remainingAt(clock.now()))
        clock.advance(540_000)
        assertEquals(0L, unlocks.remaining("video.a"))
    }

    @Test fun retryAndActiveGrantReuseCannotDebitAgainOrExtendExpiry() = runBlocking {
        credit(3)
        val first = (unlocks.redeem("video.a", "first") as TimedUnlockResult.Granted).lease
        assertTrue((unlocks.redeem("video.a", "first") as TimedUnlockResult.Granted).reused)
        clock.advance(120_000)
        val alias = (unlocks.redeem("video.a", "second") as TimedUnlockResult.Granted).lease
        assertEquals(first.expiresAt, alias.expiresAt)
        assertEquals(2, db.creditDao().balance(day))
        clock.advance(480_000)
        assertEquals(0L, (unlocks.redeem("video.a", "second") as TimedUnlockResult.Granted).lease.remainingAt(clock.now()))
        assertEquals(2, db.creditDao().balance(day))
        assertFalse((unlocks.redeem("video.a", "third") as TimedUnlockResult.Granted).reused)
        assertEquals(1, db.creditDao().balance(day))
    }

    @Test fun concurrentRedemptionsCannotSpendSamePoint() = runBlocking {
        credit()
        val outcomes = coroutineScope { List(8) { index -> async { unlocks.redeem("video.app$index", "request$index") } }.map { it.await() } }
        assertEquals(1, outcomes.count { it is TimedUnlockResult.Granted })
        assertEquals(7, outcomes.count { it == TimedUnlockResult.InsufficientCredits })
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun grantWriteFailureRollsBackCreditDebit() = runBlocking {
        credit()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_grant BEFORE INSERT ON timed_unlocks
            BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END""")
        try { unlocks.redeem("video.a", "grant"); fail("Expected injected write failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(1, db.creditDao().balance(day))
        assertTrue(db.focusSessionDao().allUnlocks().isEmpty())
    }

    @Test fun selectedAppCannotBeRedeemedDuringRunningFocusButCanWhilePaused() = runBlocking {
        credit()
        val session = sessions.start(plan(), 25, setOf("video.a"), "start")
        assertEquals(TimedUnlockResult.BlockedByFocus, unlocks.redeem("video.a", "grant"))
        assertEquals(1, db.creditDao().balance(day))
        sessions.pause(session.sessionId)
        assertTrue(unlocks.redeem("video.a", "grant") is TimedUnlockResult.Granted)
    }

    @Test fun invalidStartCannotReplaceExistingSession() = runBlocking {
        val id = plan()
        val first = sessions.start(id, 25, emptySet(), "start")
        assertEquals(first, sessions.start(id, 25, emptySet(), "start"))
        try { sessions.start(id, 0, emptySet(), "invalid"); fail("Duration must be bounded") }
        catch (_: IllegalArgumentException) { }
        try { sessions.start(id, 15, emptySet(), "another"); fail("Unfinished session must not be replaced") }
        catch (_: IllegalStateException) { }
        assertEquals(first, sessions.current())
        assertNull(sessions.pause("stale-id"))
    }
}
