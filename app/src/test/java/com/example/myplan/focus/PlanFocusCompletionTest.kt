package com.example.myplan.focus

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.session.*
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PlanFocusCompletionTest {
    private lateinit var db: PlanDatabase
    private lateinit var plans: PlanRepository
    private lateinit var sessions: FocusSessionRepository
    private lateinit var actions: PlanActions
    private lateinit var time: FocusTime

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java).allowMainThreadQueries().build()
        time = FocusTime(LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            100_000, 1, LocalDate.now().toString())
        plans = PlanRepository(db)
        sessions = FocusSessionRepository(db, FocusClock { time })
        actions = PlanActions(plans, AlarmScheduler(context))
    }
    @After fun close() { db.close() }
    private fun elapse() { time = time.copy(wallTimeMillis = time.wallTimeMillis + 60_000, elapsedRealtimeMillis = time.elapsedRealtimeMillis + 60_000) }
    private suspend fun plan(): Long {
        val id = plans.insertPlan(PlanEntity(name = "阅读", repeatMode = "EVERY_DAY"))
        plans.insertReminder(ReminderEntity(planId = id, hour = 9, minute = 0))
        plans.insertReminder(ReminderEntity(planId = id, hour = 20, minute = 0))
        return id
    }

    @Test fun sharedCompletionAwardsOnceAndPreservesRepeatAndReminders() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, setOf("video.app"), "session")
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertEquals(0, db.creditDao().balance(time.date))
        elapse()
        assertTrue(actions.completeFocusSession(session.sessionId, sessions))
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertTrue(plans.getPlanById(id)!!.isCompleted)
        assertEquals("EVERY_DAY", plans.getPlanById(id)!!.repeatMode)
        assertEquals(2, plans.getRemindersByPlanId(id).size)
        assertEquals(1, db.creditDao().balance(time.date))
        assertEquals(1, db.dailyCheckinDao().getByDate(time.date)!!.completedCount)
        assertEquals(FocusSessionEntity.COMPLETED, sessions.current()!!.status)
    }

    @Test fun manualCompletionDuringFocusDoesNotAddAnotherReward() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, setOf("video.app"), "session")
        actions.setCompleted(id, true)
        elapse()
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertEquals(1, db.creditDao().balance(time.date))
        assertEquals(FocusSessionEntity.CANCELLED, sessions.current()!!.status)
    }

    @Test fun cancelledSessionCannotCompleteOrRewardItsPlan() = runBlocking {
        val id = plan()
        val session = sessions.start(id, 1, setOf("video.app"), "session")
        sessions.cancel(session.sessionId)
        elapse()
        assertFalse(actions.completeFocusSession(session.sessionId, sessions))
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(time.date))
    }
}
