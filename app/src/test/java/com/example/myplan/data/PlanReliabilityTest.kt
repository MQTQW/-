package com.example.myplan.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.CreditRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import android.app.AlarmManager
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PlanReliabilityTest {
    private lateinit var db: PlanDatabase
    private lateinit var repo: PlanRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun failedReminderInsertRollsBackEntireEdit() = runBlocking {
        val (id, _) = repo.savePlanWithReminders(
            PlanEntity(name = "原计划"), listOf(ReminderEntity(planId = 0, hour = 8, minute = 0)))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_reminder BEFORE INSERT ON reminders
            WHEN NEW.minute = 59 BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END
        """.trimIndent())
        try {
            repo.savePlanWithReminders(repo.getPlanById(id)!!.copy(name = "未完成的修改"),
                listOf(ReminderEntity(planId = id, hour = 9, minute = 59)))
            fail("Expected the injected write failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("原计划", repo.getPlanById(id)!!.name)
        assertEquals(8, repo.getRemindersByPlanId(id).single().hour)
    }

    @Test fun undoDeleteRestoresExistingId() = runBlocking {
        val (id, _) = repo.savePlanWithReminders(PlanEntity(name = "可撤销"), emptyList())
        val plan = repo.getPlanById(id)!!
        repo.deletePlan(plan)
        repo.savePlanWithReminders(plan, emptyList())
        assertEquals("可撤销", repo.getPlanById(id)?.name)
    }

    @Test fun savingThroughSharedVoiceAndManualPathRegistersThenRemovesAlarm() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = PlanActions(repo, AlarmScheduler(context))
        val id = actions.save(PlanEntity(name = "语音提醒"),
            listOf(ReminderEntity(planId = 0, hour = 8, minute = 30)))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        actions.save(repo.getPlanById(id)!!, emptyList())
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test fun disabledPlanRemainsDisabledAfterRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = PlanActions(repo, AlarmScheduler(context))
        val id = actions.save(PlanEntity(name = "暂停", isEnabled = false),
            listOf(ReminderEntity(planId = 0, hour = 8, minute = 0)))
        val plan = repo.getPlanById(id)!!
        val reminders = repo.getRemindersByPlanId(id)
        actions.delete(plan)
        actions.save(plan, reminders)
        assertFalse(repo.getPlanById(id)!!.isEnabled)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun undoSpentCompletionCannotEarnAnotherCredit() = runBlocking {
        val (id, _) = repo.savePlanWithReminders(PlanEntity(name = "完成计划"), emptyList())
        val today = LocalDate.now().toString()
        val credits = CreditRepository(db)
        repo.setCompleted(id, true)
        repo.setCompleted(id, true)
        assertEquals(1, db.dailyCheckinDao().getByDate(today)!!.completedCount)
        assertTrue(credits.consume())
        repo.setCompleted(id, false)
        assertEquals(0, db.dailyCheckinDao().getByDate(today)!!.completedCount)
        repo.setCompleted(id, true)
        assertFalse(CreditRepository(db).consume())
        assertEquals(1, db.dailyCheckinDao().getByDate(today)!!.completedCount)
    }

    @Test fun concurrentConsumersCannotSpendSameCredit() = runBlocking {
        val credits = CreditRepository(db)
        credits.awardTask(1, 1)
        val results = kotlinx.coroutines.coroutineScope {
            List(10) { async { credits.consume() } }.map { it.await() }
        }
        assertEquals(1, results.count { it })
        assertFalse(CreditRepository(db).awardTask(1, 1))
    }

    @Test fun retryingSameAppUnlockDoesNotChargeTwice() = runBlocking {
        val credits = CreditRepository(db)
        credits.awardTask(1, 2)
        assertTrue(credits.consume(appPackage = "video.a"))
        assertTrue(CreditRepository(db).consume(appPackage = "video.a"))
        assertEquals(1, db.creditDao().balance(LocalDate.now().toString()))
    }

    @Test fun nextDayClearsCompletionButKeepsHistoryAndReminders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = PlanActions(repo, AlarmScheduler(context))
        val id = actions.save(PlanEntity(name = "周一", repeatMode = "CUSTOM", customDays = "1"),
            listOf(ReminderEntity(planId = 0, hour = 8, minute = 0)))
        repo.setCompleted(id, true, "2026-10-19")
        repo.resetDailyCompletions("2026-10-20")
        assertFalse(repo.getPlanById(id)!!.isCompleted)
        assertEquals(1, db.dailyCheckinDao().getByDate("2026-10-19")!!.completedCount)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        repo.resetDailyCompletions("2026-10-19")
        assertTrue(repo.getPlanById(id)!!.isCompleted)
    }

    @Test fun invalidVoiceTimeDoesNotCreatePartialPlan() = runBlocking {
        try {
            repo.savePlanWithReminders(PlanEntity(name = "无效时间"),
                listOf(ReminderEntity(planId = 0, hour = 27, minute = 0)))
            fail("Invalid times must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.planDao().getAllPlansSync().isEmpty())
    }

    @Test fun backupContainsPlansTimesAndCompletionHistory() = runBlocking {
        val (id, _) = repo.savePlanWithReminders(PlanEntity(name = "备份计划"),
            listOf(ReminderEntity(planId = 0, hour = 8, minute = 30)))
        repo.setCompleted(id, true, "2026-10-15")
        val backup = org.json.JSONObject(PlanBackup.export(db))
        assertEquals("my-plan-backup", backup.getString("format"))
        val plan = backup.getJSONArray("plans").getJSONObject(0)
        assertEquals("备份计划", plan.getString("name"))
        assertEquals(30, plan.getJSONArray("reminders").getJSONObject(0).getInt("minute"))
        assertEquals("2026-10-15", backup.getJSONArray("checkins").getJSONObject(0).getString("date"))
        assertEquals(1, backup.getJSONArray("completions").length())
    }

    @Test @Config(sdk = [35]) fun deniedExactAlarmPermissionPreservesPlanAndRecoversAfterGrant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val actions = PlanActions(repo, AlarmScheduler(context))
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val id = try {
            actions.save(PlanEntity(name = "等待授权"), listOf(ReminderEntity(planId = 0, hour = 8, minute = 30)))
            fail("Expected permission failure")
            0L
        } catch (e: com.example.myplan.notification.AlarmSyncException) { e.planId }
        assertNotNull(repo.getPlanById(id))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        actions.sync(id)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun staleBroadcastCannotReviveDeletedOrPausedReminder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = PlanActions(repo, AlarmScheduler(context))
        val id = actions.save(PlanEntity(name = "旧提醒"), listOf(ReminderEntity(planId = 0, hour = 8, minute = 0)))
        val reminder = repo.getRemindersByPlanId(id).single()
        actions.setEnabled(id, false)
        assertFalse(actions.fire(id, reminder.id) { fail("Paused plan must not notify") })
        actions.save(repo.getPlanById(id)!!.copy(isEnabled = true), emptyList())
        assertFalse(actions.fire(id, reminder.id) { fail("Deleted reminder must not notify") })
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
