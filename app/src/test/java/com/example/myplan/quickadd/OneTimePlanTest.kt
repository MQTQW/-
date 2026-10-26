package com.example.myplan.quickadd

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.PlanBackup
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class OneTimePlanTest {
    private lateinit var db: PlanDatabase
    private lateinit var repo: PlanRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var actions: PlanActions
    private val future = System.currentTimeMillis() + 3_600_000
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java).allowMainThreadQueries().build()
        repo = PlanRepository(db); scheduler = AlarmScheduler(context); actions = PlanActions(repo, scheduler)
    }
    @After fun close() { db.close() }
    private suspend fun create(token: String? = null) = actions.save(PlanEntity(name = "一次取件", repeatMode = "ONCE", oneTimeAt = future, creationToken = token),
        listOf(ReminderEntity(planId = 0, hour = 18, minute = 0)))

    @Test fun oneTimeHasNoNextDayOccurrence() = runBlocking {
        val id = create(); val plan = repo.getPlanById(id)!!; val rem = repo.getRemindersByPlanId(id).single()
        assertEquals(future, scheduler.nextFireTime(plan, rem, future - 1))
        assertNull(scheduler.nextFireTime(plan, rem, future))
    }
    @Test fun deliveryConsumedExactlyOnce() = runBlocking {
        val id = create(); val rem = repo.getRemindersByPlanId(id).single(); var count = 0
        assertTrue(actions.fire(id, rem.id, rem.scheduleVersion) { count++ })
        assertFalse(actions.fire(id, rem.id, rem.scheduleVersion) { count++ })
        assertEquals(1, count)
    }
    @Test fun completionSurvivesMidnightAndSuppressesAlarm() = runBlocking {
        val id = create(); actions.setCompleted(id, true)
        repo.resetDailyCompletions(LocalDate.now().plusDays(1).toString())
        val plan = repo.getPlanById(id)!!; assertTrue(plan.isCompleted)
        assertNull(scheduler.nextFireTime(plan, repo.getRemindersByPlanId(id).single()))
    }
    @Test fun retryWithSameTokenDoesNotDuplicateOrOverwrite() = runBlocking {
        val first = create("same-token")
        repo.updatePlan(repo.getPlanById(first)!!.copy(name = "修改后的名字"))
        val retried = create("same-token")
        assertEquals(first, retried); assertEquals(1, repo.getAllPlans().size)
        assertEquals("修改后的名字", repo.getPlanById(first)!!.name)
    }
    @Test fun onceCannotEarnAnotherRewardOnDifferentDay() = runBlocking {
        val id = create(); val today = LocalDate.now().toString(); val tomorrow = LocalDate.now().plusDays(1).toString()
        repo.setCompleted(id, true, today); repo.setCompleted(id, false, tomorrow); repo.setCompleted(id, true, tomorrow)
        assertEquals(1, db.completionDao().getAll().size)
        assertEquals(today, db.completionDao().getAll().single().date)
        assertEquals(1, db.dailyCheckinDao().getByDate(today)!!.completedCount)
        assertNull(db.dailyCheckinDao().getByDate(tomorrow))
    }
    @Test fun savingPendingOnceDoesNotCreateFakeCompletionHistory() = runBlocking {
        create(); assertTrue(db.completionDao().getAll().isEmpty())
    }
    @Test fun exportRetainsAbsoluteDate() = runBlocking {
        create(); val json = org.json.JSONObject(PlanBackup.export(db))
        assertEquals(3, json.getInt("version")); assertEquals(future, json.getJSONArray("plans").getJSONObject(0).getLong("oneTimeAt"))
    }
}
