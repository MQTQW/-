package com.example.myplan.focus.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.ui.lock.LockOverlayActivity
import com.example.myplan.focus.util.FocusConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Accessibility service that monitors app window changes.
 * When a locked app is detected, goes home and shows LockOverlayActivity.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usageRepo: AppUsageRepository

    private val lockedPackages = mutableSetOf<String>()
    private var lastLockedShown: String? = null
    private var overlayShowing = false
    private var currentForegroundPackage: String? = null

    private val lockUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FocusConstants.ACTION_DAILY_RESET -> {
                    lockedPackages.clear()
                    lastLockedShown = null
                    overlayShowing = false
                }
                FocusConstants.ACTION_USAGE_UPDATED -> {
                    if (intent.getBooleanExtra("locked", false)) {
                        val pkg = intent.getStringExtra("packageName") ?: return
                        lockedPackages.add(pkg)
                        if (currentForegroundPackage == pkg && !overlayShowing) {
                            lastLockedShown = null
                            performLock(pkg)
                        }
                    }
                    if (intent.getBooleanExtra("unlocked", false)) {
                        lockedPackages.clear()
                        lastLockedShown = null
                        overlayShowing = false
                    }
                    if (intent.getBooleanExtra("unlock_single", false)) {
                        val pkg = intent.getStringExtra("packageName") ?: return
                        lockedPackages.remove(pkg)
                        lastLockedShown = null
                        overlayShowing = false
                    }
                    if (intent.getBooleanExtra("overlay_dismissed", false)) {
                        overlayShowing = false
                        lastLockedShown = null
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as MyPlanApp
        usageRepo = AppUsageRepository(applicationContext, app.focusDb)

        val filter = IntentFilter().apply {
            addAction(FocusConstants.ACTION_DAILY_RESET)
            addAction(FocusConstants.ACTION_USAGE_UPDATED)
        }
        registerReceiver(lockUpdateReceiver, filter, RECEIVER_EXPORTED)

        scope.launch {
            val locked = usageRepo.getLockedAppsForToday()
            lockedPackages.clear()
            lockedPackages.addAll(locked.map { it.packageName })
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        currentForegroundPackage = packageName

        if (packageName == this.packageName) {
            lastLockedShown = null
            return
        }

        if (lockedPackages.contains(packageName) && !overlayShowing && lastLockedShown != packageName) {
            performLock(packageName)
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

        performGlobalAction(GLOBAL_ACTION_HOME)

        handler.postDelayed({
            LockOverlayActivity.show(this, packageName, appName)
        }, 300)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (overlayShowing) return true
            val cachedPkg = currentForegroundPackage
            if (cachedPkg != null && lockedPackages.contains(cachedPkg)) {
                performLock(cachedPkg)
                return true
            }
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
    }
}
