package com.example.myplan.data

import androidx.test.core.app.ApplicationProvider
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.service.UsageMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = MyPlanApp::class, sdk = [29])
class CategoryLockTest {
    @Test fun categoryTimeoutLocksOnlyMembersAndHonorsPurchasedUnlock() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyPlanApp>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DailyRollover.ensureToday(app)
        val db = app.focusDb
        withContext(Dispatchers.IO) { db.clearAllTables() }
        val today = LocalDate.now().toString()
        db.usageLogDao().insert(DailyUsageEntity(packageName = "video.a", appName = "视频", date = today, categoryId = 1))
        db.usageLogDao().insert(DailyUsageEntity(packageName = "study.b", appName = "学习", date = today, categoryId = 2))
        db.appLimitDao().insert(AppLimitEntity(packageName = "video.a", dailyLimitMillis = 100))
        db.appLimitDao().insert(AppLimitEntity(packageName = "study.b", dailyLimitMillis = 100))
        val controller = Robolectric.buildService(UsageMonitorService::class.java).create()
        try {
            controller.get().checkCategoryLimit(1000, 100, 1)
            assertTrue(db.usageLogDao().getUsageForPackageAndDate("video.a", today)!!.isLocked)
            assertFalse(db.usageLogDao().getUsageForPackageAndDate("study.b", today)!!.isLocked)
            val lockBroadcast = shadowOf(app).broadcastIntents.last { it.getBooleanExtra("locked", false) }
            assertEquals(app.packageName, lockBroadcast.`package`)
            db.usageLogDao().unlockPackageForDate("video.a", today)
            assertEquals(listOf("video.a"), db.usageLogDao().getUnlockedAppsForDate(today))
            val restarted = Robolectric.buildService(UsageMonitorService::class.java).create()
            try {
                restarted.get().checkCategoryLimit(1000, 100, 1)
                assertFalse(db.usageLogDao().getUsageForPackageAndDate("video.a", today)!!.isLocked)
            } finally { restarted.destroy() }
        } finally { controller.destroy() }
    }
}
