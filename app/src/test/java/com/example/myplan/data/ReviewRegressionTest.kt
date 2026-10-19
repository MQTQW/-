package com.example.myplan.data

import android.app.AlarmManager
import android.app.NotificationManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.ui.home.HomeViewModel
import com.example.myplan.notification.*
import com.example.myplan.ui.screen.planedit.PlanEditViewModel
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Robolectric resets SQLite between tests; production database singletons must not cross that boundary. */
fun resetAppDatabases() {
    listOf(PlanDatabase::class.java, FocusDatabase::class.java).forEach { type ->
        val field = type.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? androidx.room.RoomDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = MyPlanApp::class, sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRegressionTest {
    @Before fun reset() { resetAppDatabases() }

    private suspend fun app(): MyPlanApp {
        val app = ApplicationProvider.getApplicationContext<MyPlanApp>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DailyRollover.ensureToday(app)
        withContext(Dispatchers.IO) { PlanDatabase.getInstance(app).clearAllTables(); app.focusDb.clearAllTables() }
        return app
    }

    @Test fun repeatedSaveCreatesOnlyOnePlanAndKeepsGeneratedId() = runBlocking {
        val app = app()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = PlanEditViewModel(app)
        val store = ViewModelStore().apply { put("edit", vm) }
        try {
            vm.loadPlan(null); vm.updateName("只保存一次")
            val completed = AtomicInteger()
            vm.save { completed.incrementAndGet() }; vm.save { completed.incrementAndGet() }
            withTimeout(5000) { while (vm.uiState.value.isSaving) delay(10) }
            assertEquals(1, completed.get())
            assertEquals(1, PlanDatabase.getInstance(app).planDao().getAllPlansSync().size)
            assertTrue(vm.uiState.value.id > 0)
            vm.save { completed.incrementAndGet() }
            withTimeout(5000) { while (vm.uiState.value.isSaving) delay(10) }
            assertEquals(1, PlanDatabase.getInstance(app).planDao().getAllPlansSync().size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun disablingRuleKeepsItVisibleAndReleasesOnlyUnjustifiedLocks() = runBlocking {
        val app = app(); val today = LocalDate.now().toString(); val repo = LimitRepository(app.focusDb)
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "video.a", appName = "A", date = today, totalTimeMillis = 1000))
        val first = repo.insertLimit(AppLimitEntity(packageName = "video.a", dailyLimitMillis = 100))
        val second = repo.insertLimit(AppLimitEntity(packageName = "video.a", dailyLimitMillis = 500))
        repo.setLimitEnabled(first, false)
        assertEquals(2, repo.getAllLimits().first().size)
        assertTrue(app.focusDb.usageLogDao().getUsageForPackageAndDate("video.a", today)!!.isLocked)
        repo.deleteLimit(second)
        assertFalse(app.focusDb.usageLogDao().getUsageForPackageAndDate("video.a", today)!!.isLocked)
        assertFalse(repo.getAllLimits().first().single().isEnabled)
    }

    @Test fun editedReminderRejectsOldVersionAndAcceptsCurrentVersion() = runBlocking {
        val app = app(); val repo = PlanRepository(PlanDatabase.getInstance(app)); val actions = PlanActions(repo, AlarmScheduler(app))
        val id = actions.save(PlanEntity(name = "计划"), listOf(ReminderEntity(planId = 0, hour = 8, minute = 30)))
        val old = repo.getRemindersByPlanId(id).single()
        actions.save(repo.getPlanById(id)!!, listOf(old.copy(hour = 14)))
        assertFalse(actions.fire(id, old.id, old.scheduleVersion) { fail("Old event must be ignored") })
        val current = repo.getRemindersByPlanId(id).single()
        assertTrue(actions.fire(id, current.id, current.scheduleVersion) { })
    }

    @Test fun alarmEntryAndNotificationHaveDifferentIdentities() = runBlocking {
        val app = app(); val id = 500L
        NotificationHelper.showReminderNotification(app, id, "计划", null)
        val pi = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(id.toInt()).contentIntent
        AlarmScheduler(app).scheduleAlarm(501, id, System.currentTimeMillis() + 60_000, "计划", null)
        assertNotEquals(pi, app.getSystemService(AlarmManager::class.java).nextAlarmClock.showIntent)
        assertEquals(id, shadowOf(pi).savedIntent.getLongExtra("plan_id", -1))
    }

    @Test fun actualUsageCollectionCarriesCategoryMembership() = runBlocking {
        val app = app(); val repo = LimitRepository(app.focusDb)
        repo.insertLimit(AppLimitEntity(packageName = "video.a", categoryId = 42, dailyLimitMillis = 10000))
        val usage = AppUsageRepository(app, app.focusDb)
        usage.updateUsage("video.a", "视频", 1000)
        assertEquals(42L, usage.getUsageForPackageAndDate("video.a", usage.getToday())!!.categoryId)
        assertEquals(1000L, usage.getTotalForCategoryOnDate(42, usage.getToday()))
        assertTrue(repo.getCategoryLimitsSync().isEmpty())
        repo.insertLimit(AppLimitEntity(categoryId = 42, dailyLimitMillis = 500))
        assertTrue(app.focusDb.usageLogDao().getUsageForPackageAndDate("video.a", usage.getToday())!!.isLocked)
    }

    @Test fun homeSubscriptionMovesToNewDate() = runBlocking {
        val app = app(); Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = object : Clock() {
            var value = Instant.parse("2026-10-19T08:00:00Z")
            override fun instant() = value
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        }
        val dates = DateProvider(clock)
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "old.app", appName = "旧", date = "2026-10-19"))
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "new.app", appName = "新", date = "2026-10-20"))
        val vm = HomeViewModel(app, dates); val store = ViewModelStore().apply { put("home", vm) }
        try {
            withTimeout(5000) { while (vm.state.value.todayUsage.isEmpty()) delay(10) }
            assertEquals("old.app", vm.state.value.todayUsage.single().packageName)
            clock.value = clock.value.plusSeconds(86400); dates.refresh()
            withTimeout(5000) { while (vm.state.value.todayUsage.singleOrNull()?.packageName != "new.app") delay(10) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
