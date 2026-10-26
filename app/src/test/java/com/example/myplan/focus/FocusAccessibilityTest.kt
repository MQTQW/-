package com.example.myplan.focus

import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.MyPlanApp
import com.example.myplan.MainActivity
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.resetAppDatabases
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.service.AppLockAccessibilityService
import com.example.myplan.focus.session.FocusClock
import com.example.myplan.focus.session.FocusTime
import com.example.myplan.focus.session.TimedUnlockEntity
import com.example.myplan.focus.ui.lock.LockOverlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = MyPlanApp::class, sdk = [29])
class FocusAccessibilityTest {
    @Before fun reset() { resetAppDatabases() }

    private fun foreground(service: AppLockAccessibilityService, packageName: String, windowClass: String? = null) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
            className = windowClass ?: if (packageName == service.packageName) MainActivity::class.java.name else "test.Activity"
        })
    }

    private fun state(service: AppLockAccessibilityService, name: String): Any? =
        AppLockAccessibilityService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private suspend fun awaitRefresh(service: AppLockAccessibilityService, dispatcher: TestDispatcher) = withTimeout(5000) {
        while (true) {
            dispatcher.scheduler.runCurrent()
            val job = state(service, "refreshJob") as? Job
            delay(10)
            dispatcher.scheduler.runCurrent()
            if (job?.isCompleted == true && state(service, "refreshJob") === job) return@withTimeout
        }
    }

    private suspend fun awaitPendingOverlay(service: AppLockAccessibilityService, dispatcher: TestDispatcher) {
        try {
            withTimeout(5000) {
                while (state(service, "pendingLock") == null) {
                    dispatcher.scheduler.runCurrent()
                    delay(10)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("No pending overlay: foreground=${state(service, "currentForegroundPackage")}, " +
                "refresh=${state(service, "refreshJob")}, boundary=${state(service, "boundaryJob")}, " +
                "homeActions=${shadowOf(service).globalActionsPerformed}", e)
        }
    }

    private suspend fun nextOverlay(app: MyPlanApp, dispatcher: TestDispatcher): Intent = withTimeout(5000) {
        var intent: Intent? = null
        while (intent == null) {
            dispatcher.scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            delay(20)
            dispatcher.scheduler.runCurrent()
            intent = shadowOf(app).nextStartedActivity
        }
        intent
    }

    @Test fun entitlementExpiryRelocksAlreadyOpenAppAndReopeningCannotBypassOverlay() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyPlanApp>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DailyRollover.ensureToday(app)
        val db = PlanDatabase.getInstance(app)
        withContext(Dispatchers.IO) { db.clearAllTables(); app.focusDb.clearAllTables() }
        var time = FocusTime(System.currentTimeMillis(), 100_000L, 1, LocalDate.now().toString())
        val clock = FocusClock { time }
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "video.app", appName = "视频",
            date = time.date, totalTimeMillis = 1000L))
        LimitRepository(app.focusDb).insertLimit(AppLimitEntity(packageName = "video.app", dailyLimitMillis = 100L))
        db.focusSessionDao().insertUnlock(TimedUnlockEntity("lease", "video.app", time.date,
            500L, 1, time.wallTimeMillis, time.wallTimeMillis + 500L,
            time.elapsedRealtimeMillis, time.elapsedRealtimeMillis + 500L, time.bootCount))
        shadowOf(app.getSystemService(InputMethodManager::class.java)).setEnabledInputMethodInfoList(
            listOf(InputMethodInfo("keyboard.test", "KeyboardService", "测试键盘", null)))
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val controller = Robolectric.buildService(AppLockAccessibilityService::class.java).create()
        try {
            AppLockAccessibilityService::class.java.getDeclaredField("enforcement").apply {
                isAccessible = true
                set(controller.get(), FocusEnforcement(app, clock))
            }
            foreground(controller.get(), "video.app")
            awaitRefresh(controller.get(), dispatcher)
            assertNull(shadowOf(app).nextStartedActivity)
            foreground(controller.get(), "keyboard.test")
            awaitRefresh(controller.get(), dispatcher)
            // Only the expiry boundary runs: no foreground event and no monitor tick occurs.
            time = time.copy(wallTimeMillis = time.wallTimeMillis + 501L,
                elapsedRealtimeMillis = time.elapsedRealtimeMillis + 501L)
            dispatcher.scheduler.advanceTimeBy(501L)
            dispatcher.scheduler.runCurrent()
            awaitPendingOverlay(controller.get(), dispatcher)
            foreground(controller.get(), "launcher.test")
            foreground(controller.get(), app.packageName, "android.appwidget.AppWidgetHostView")
            val first = nextOverlay(app, dispatcher)
            assertEquals(LockOverlayActivity::class.java.name, first.component!!.className)
            assertEquals("video.app", first.getStringExtra(LockOverlayActivity.EXTRA_PACKAGE_NAME))
            // A singleInstance lock activity can background without being destroyed.
            foreground(controller.get(), app.packageName)
            foreground(controller.get(), "video.app")
            val second = nextOverlay(app, dispatcher)
            assertEquals("video.app", second.getStringExtra(LockOverlayActivity.EXTRA_PACKAGE_NAME))
            foreground(controller.get(), app.packageName, LockOverlayActivity::class.java.name)
            foreground(controller.get(), "video.app")
            awaitPendingOverlay(controller.get(), dispatcher)
            // Explicitly returning to Focus cancels the pending overlay; widget events do not.
            foreground(controller.get(), app.packageName, MainActivity::class.java.name)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            repeat(25) { dispatcher.scheduler.runCurrent(); delay(10) }
            assertNull(shadowOf(app).nextStartedActivity)
            foreground(controller.get(), "video.app")
            assertEquals("video.app", nextOverlay(app, dispatcher).getStringExtra(LockOverlayActivity.EXTRA_PACKAGE_NAME))
        } finally {
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun reconnectRecoversAlreadyForegroundAppAndExpiresWithoutAnyNewWindowEvent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyPlanApp>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DailyRollover.ensureToday(app)
        val db = PlanDatabase.getInstance(app)
        withContext(Dispatchers.IO) { db.clearAllTables(); app.focusDb.clearAllTables() }
        var time = FocusTime(System.currentTimeMillis(), 100_000L, 1, LocalDate.now().toString())
        val clock = FocusClock { time }
        app.focusDb.usageLogDao().insert(DailyUsageEntity(packageName = "video.app", appName = "视频",
            date = time.date, totalTimeMillis = 1000L))
        LimitRepository(app.focusDb).insertLimit(AppLimitEntity(packageName = "video.app", dailyLimitMillis = 100L))
        db.focusSessionDao().insertUnlock(TimedUnlockEntity("lease", "video.app", time.date,
            500L, 1, time.wallTimeMillis, time.wallTimeMillis + 500L,
            time.elapsedRealtimeMillis, time.elapsedRealtimeMillis + 500L, time.bootCount))
        shadowOf(app.getSystemService(InputMethodManager::class.java)).setEnabledInputMethodInfoList(
            listOf(InputMethodInfo("keyboard.test", "KeyboardService", "测试键盘", null)))
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val controller = Robolectric.buildService(AppLockAccessibilityService::class.java).create()
        try {
            AppLockAccessibilityService::class.java.getDeclaredField("enforcement").apply {
                isAccessible = true
                set(controller.get(), FocusEnforcement(app, clock))
            }
            val appWindow = AccessibilityWindowInfo.obtain().also { window ->
                shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                shadowOf(window).setFocused(true)
                shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = "video.app" })
            }
            val keyboardWindow = AccessibilityWindowInfo.obtain().also { window ->
                shadowOf(window).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                shadowOf(window).setActive(true)
                shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = "keyboard.test" })
            }
            shadowOf(controller.get()).setWindows(listOf(keyboardWindow, appWindow))
            AppLockAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
                isAccessible = true
                invoke(controller.get())
            }
            awaitRefresh(controller.get(), dispatcher)
            assertNull(shadowOf(app).nextStartedActivity)
            time = time.copy(wallTimeMillis = time.wallTimeMillis + 501L,
                elapsedRealtimeMillis = time.elapsedRealtimeMillis + 501L)
            dispatcher.scheduler.advanceTimeBy(501L)
            dispatcher.scheduler.runCurrent()
            val overlay = nextOverlay(app, dispatcher)
            assertEquals("video.app", overlay.getStringExtra(LockOverlayActivity.EXTRA_PACKAGE_NAME))
        } finally {
            controller.destroy()
            Dispatchers.resetMain()
        }
    }
}
