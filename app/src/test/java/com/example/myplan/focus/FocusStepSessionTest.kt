package com.example.myplan.focus

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.PlanStepEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.repository.PlanStepRepository
import com.example.myplan.focus.session.FocusClock
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.session.FocusSessionRepository
import com.example.myplan.focus.session.FocusTime
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class FocusStepSessionTest {
    private lateinit var db: PlanDatabase
    private lateinit var plans: PlanRepository
    private lateinit var actions: PlanActions
    private lateinit var sessions: FocusSessionRepository
    private val clock = TestClock()
    private val day get() = clock.time.date

    private class TestClock : FocusClock {
        var time = FocusTime(System.currentTimeMillis(), 100_000L, 1, LocalDate.now().toString())
        override fun now() = time
        fun finishMinute() { time = time.copy(wallTimeMillis = time.wallTimeMillis + 60_000L,
            elapsedRealtimeMillis = time.elapsedRealtimeMillis + 60_000L) }
    }

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanRepository(db)
        actions = PlanActions(plans, AlarmScheduler(context))
        sessions = FocusSessionRepository(db, clock)
    }
    @After fun close() { db.close() }

    private suspend fun planWithSteps(): Long = actions.save(PlanEntity(name = "面试准备"),
        listOf(ReminderEntity(planId = 0, hour = 20, minute = 0)),
        listOf(PlanStepDraft(title = "整理项目经历", focusMinutes = 15), PlanStepDraft(title = "练习自我介绍")))

    @Test fun stepMustBelongToSelectedPlanAndBeUnfinishedForCurrentDay() = runBlocking {
        val first = planWithSteps()
        val other = planWithSteps()
        val foreign = db.planStepDao().getSteps(other).first()
        try { sessions.start(first, 1, emptySet(), stepId = foreign.id); fail("Foreign step must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertNull(sessions.current())
        val step = db.planStepDao().getSteps(first).first()
        db.planStepDao().setCompleted(first, step.id, true, day)
        try { sessions.start(first, 1, emptySet(), stepId = step.id); fail("Completed step must be rejected") }
        catch (_: IllegalArgumentException) { }
        db.planStepDao().setCompleted(first, step.id, true, LocalDate.parse(day).minusDays(1).toString())
        assertEquals(step.id, sessions.start(first, 1, emptySet(), stepId = step.id).stepId)
    }

    @Test fun completedOneTimeStepCannotBeFocusedAgainOnAnotherDay() = runBlocking {
        val id = plans.insertPlan(PlanEntity(name = "一次性准备", repeatMode = "ONCE",
            oneTimeAt = System.currentTimeMillis() + 3_600_000))
        val stepId = db.planStepDao().insert(PlanStepEntity(planId = id, position = 0, title = "准备资料",
            isCompleted = true, completedDate = LocalDate.parse(day).minusDays(1).toString()))
        try { sessions.start(id, 1, emptySet(), stepId = stepId); fail("One-time completion persists") }
        catch (_: IllegalArgumentException) { }
        assertNull(sessions.current())
    }

    @Test fun completingStepElsewhereCancelsSessionWithoutCompletingParentOrRewarding() = runBlocking {
        val id = planWithSteps()
        val step = db.planStepDao().getSteps(id).first()
        val session = sessions.start(id, 1, setOf("video.app"), stepId = step.id)
        assertTrue(PlanStepRepository(db).setCompleted(id, step.id, true, day))
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun deletingSelectedStepCancelsSession() = runBlocking {
        val id = planWithSteps()
        val step = db.planStepDao().getSteps(id).first()
        sessions.start(id, 1, emptySet(), stepId = step.id)
        db.planStepDao().deleteForPlan(id)
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun confirmingStepCompletesOnlyThatStepAndLeavesParentReminderAndCreditsUntouched() = runBlocking {
        val id = planWithSteps()
        val steps = db.planStepDao().getSteps(id)
        val reminder = plans.getRemindersByPlanId(id).single()
        val session = sessions.start(id, 1, emptySet(), stepId = steps.first().id)
        assertEquals(steps.first().title, session.stepTitle)
        clock.finishMinute()
        assertTrue(actions.completeFocusSession(session.sessionId, sessions))
        assertTrue(db.planStepDao().getStep(id, steps.first().id)!!.isDoneOn("EVERY_DAY", day))
        assertFalse(db.planStepDao().getStep(id, steps.last().id)!!.isDoneOn("EVERY_DAY", day))
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(reminder, plans.getRemindersByPlanId(id).single())
        assertEquals(0, db.creditDao().balance(day))
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertEquals(FocusSessionEntity.COMPLETED, sessions.current()!!.status)
    }

    @Test fun wholePlanConfirmationStillAwardsExactlyOnePoint() = runBlocking {
        val id = planWithSteps()
        val session = sessions.start(id, 1, emptySet())
        clock.finishMinute()
        assertTrue(actions.completeFocusSession(session.sessionId, sessions))
        assertTrue(plans.getPlanById(id)!!.isCompleted)
        assertEquals(1, db.creditDao().balance(day))
        assertTrue(db.planStepDao().getSteps(id).none { it.isCompleted })
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertEquals(1, db.creditDao().balance(day))
    }

    @Test fun finishingLastStepStillRequiresSeparateWholePlanCheckIn() = runBlocking {
        val id = planWithSteps()
        val steps = db.planStepDao().getSteps(id)
        assertTrue(PlanStepRepository(db).setCompleted(id, steps.last().id, true, day))
        val session = sessions.start(id, 1, emptySet(), stepId = steps.first().id)
        clock.finishMinute()
        assertTrue(actions.completeFocusSession(session.sessionId, sessions))
        assertTrue(db.planStepDao().getSteps(id).all { it.isDoneOn("EVERY_DAY", day) })
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(day))
    }

    @Test fun failedSessionWriteRollsBackStepConfirmation() = runBlocking {
        val id = planWithSteps()
        val step = db.planStepDao().getSteps(id).first()
        val session = sessions.start(id, 1, emptySet(), stepId = step.id)
        clock.finishMinute()
        sessions.reconcile()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_step_finish BEFORE INSERT ON focus_sessions
            WHEN NEW.status = 'COMPLETED' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END""")
        try { actions.completeFocusSession(session.sessionId, sessions); fail("Expected simulated write failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertFalse(db.planStepDao().getStep(id, step.id)!!.isCompleted)
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(day))
        assertEquals(FocusSessionEntity.READY, sessions.current()!!.status)
    }

    @Test fun undoDeleteRestoresStepIdsAndCompletionTogetherWithParent() = runBlocking {
        val id = planWithSteps()
        val first = db.planStepDao().getSteps(id).first()
        db.planStepDao().setCompleted(id, first.id, true, day)
        val expected = db.planStepDao().getSteps(id)
        val reminderIds = plans.getRemindersByPlanId(id).map { it.id }
        val snapshot = actions.delete(plans.getPlanById(id)!!)
        assertTrue(db.planStepDao().getSteps(id).isEmpty())
        assertEquals(id, actions.restoreDeleted(snapshot))
        assertEquals(expected, db.planStepDao().getSteps(id))
        assertEquals(reminderIds, plans.getRemindersByPlanId(id).map { it.id })
        assertEquals(0, db.creditDao().balance(day))
    }
}
