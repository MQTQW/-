package com.example.myplan.focus.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.example.myplan.MainActivity
import com.example.myplan.focus.FocusEnforcement
import com.example.myplan.focus.FocusAppSafety
import com.example.myplan.focus.FocusEnforcementSnapshot
import com.example.myplan.focus.ui.lock.LockOverlayActivity
import com.example.myplan.quickadd.QuickCaptureActivity
import com.example.myplan.focus.util.FocusConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import com.example.myplan.data.DailyRollover
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Accessibility service that monitors app window changes.
 * When a locked app is detected, goes home and shows LockOverlayActivity.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enforcement: FocusEnforcement
    private var refreshJob: Job? = null
    private var boundaryJob: Job? = null

    private var lastLockedShown: String? = null
    private var overlayShowing = false
    private var currentForegroundPackage: String? = null
    private data class PendingLock(val token: Long, val packageName: String, val appName: String)
    private var nextLockToken = 0L
    private var pendingLock: PendingLock? = null

    private val lockUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FocusConstants.ACTION_DAILY_RESET -> {
                    lastLockedShown = null
                    overlayShowing = false
                    pendingLock = null
                }
                FocusConstants.ACTION_USAGE_UPDATED -> {
                    if (intent.getBooleanExtra("overlay_dismissed", false)) {
                        overlayShowing = false
                        lastLockedShown = null
                    }
                }
            }
            requestRefresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        enforcement = FocusEnforcement(applicationContext)

        val filter = IntentFilter().apply {
            addAction(FocusConstants.ACTION_DAILY_RESET)
            addAction(FocusConstants.ACTION_USAGE_UPDATED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, lockUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            try {
                DailyRollover.ensureToday(this@AppLockAccessibilityService)
                enforcement.observeChanges().collect { requestRefresh() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e("AppLockAccessibility", "Focus observation failed", e) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) {
            val windowClass = event.className?.toString()
            val isMain = windowClass == MainActivity::class.java.name ||
                windowClass == QuickCaptureActivity::class.java.name
            val isOverlay = windowClass == LockOverlayActivity::class.java.name
            // Launcher RemoteViews can emit our package while the launcher is foreground.
            // Only an actual app Activity can end/cancel the transition to the lock screen.
            if (!isMain && !isOverlay) {
                requestRefresh()
                return
            }
            overlayShowing = false
            if (isMain) pendingLock = null
        }
        // An IME window overlays the app; it must not hide that app from expiry enforcement.
        try {
            if (packageName !in FocusAppSafety.inputMethodPackages(this)) currentForegroundPackage = packageName
        } catch (e: Exception) {
            currentForegroundPackage = null
            Log.e("AppLockAccessibility", "Could not identify the foreground window", e)
        }
        // In particular, an expired reward must not survive until the monitor's next 30-second tick.
        requestRefresh()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // A system rebind can happen after the app is already foreground, without a new event.
        recoverForegroundWindow()
        requestRefresh()
    }

    private fun recoverForegroundWindow() {
        try {
            val inputMethods = FocusAppSafety.inputMethodPackages(this)
            val activePackage = rootInActiveWindow?.packageName?.toString()
            val applicationPackage = windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }.thenByDescending { it.layer })
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull { it !in inputMethods }
            currentForegroundPackage = when {
                !activePackage.isNullOrBlank() && activePackage !in inputMethods -> activePackage
                else -> applicationPackage
            }
        } catch (e: Exception) {
            currentForegroundPackage = null
            Log.e("AppLockAccessibility", "Could not recover the foreground window", e)
        }
    }

    private fun requestRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                if (currentForegroundPackage == null) recoverForegroundWindow()
                DailyRollover.ensureToday(this@AppLockAccessibilityService)
                applySnapshot(enforcement.snapshot())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                overlayShowing = false
                lastLockedShown = null
                pendingLock = null
                Log.e("AppLockAccessibility", "Focus check failed; will retry on the next event", e)
            }
        }
    }

    private fun applySnapshot(snapshot: FocusEnforcementSnapshot) {
        val current = snapshot.lockedPackages
        pendingLock?.takeIf { it.packageName !in current }?.let { pendingLock = null }
        if (lastLockedShown !in current) {
            lastLockedShown = null
            overlayShowing = false
        }
        // An existing singleInstance overlay may move to the background without onDestroy.
        // Seeing our window ends the pending transition, allowing a later app launch to re-lock.
        if (currentForegroundPackage == packageName ||
            (currentForegroundPackage in current && currentForegroundPackage != lastLockedShown)) {
            overlayShowing = false
        }
        currentForegroundPackage?.takeIf { it != packageName && it in current && !overlayShowing &&
            pendingLock?.packageName != it }
            ?.let { performLock(it) }
        boundaryJob?.cancel()
        boundaryJob = snapshot.nextCheckInMillis?.let { remaining ->
            scope.launch {
                delay(remaining.coerceAtLeast(1))
                requestRefresh()
            }
        }
    }

    private fun performLock(packageName: String) {
        lastLockedShown = packageName
        overlayShowing = true

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        val pending = PendingLock(++nextLockToken, packageName, appName)
        pendingLock = pending

        performGlobalAction(GLOBAL_ACTION_HOME)

        handler.postDelayed({
            scope.launch {
                try {
                    // The user may pause/cancel focus or earn a lease during the transition home.
                    val current = enforcement.snapshot()
                    if (pendingLock?.token == pending.token && current.reason(packageName) != null) {
                        pendingLock = null
                        overlayShowing = true
                        lastLockedShown = packageName
                        LockOverlayActivity.show(this@AppLockAccessibilityService, packageName, pending.appName)
                    } else if (pendingLock?.token == pending.token) {
                        pendingLock = null
                        overlayShowing = false
                        lastLockedShown = null
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (pendingLock?.token == pending.token) {
                        pendingLock = null
                        overlayShowing = false
                        lastLockedShown = null
                    }
                    Log.e("AppLockAccessibility", "Focus overlay check failed", e)
                }
            }
        }, 300)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (overlayShowing) return true
            requestRefresh()
        }

        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null)
            unregisterReceiver(lockUpdateReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
        scope.cancel()
    }
}
