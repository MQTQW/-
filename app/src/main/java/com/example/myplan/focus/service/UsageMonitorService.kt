package com.example.myplan.focus.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myplan.MainActivity
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.util.FocusConstants
import com.example.myplan.focus.util.TimeFormatter
import kotlinx.coroutines.*
import com.example.myplan.data.DailyRollover
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that monitors app usage continuously.
 * Compares actual usage against configured limits and triggers warnings/locks.
 */
class UsageMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringDate = java.time.LocalDate.now().toString()
    private val checkMutex = Mutex()
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == FocusConstants.ACTION_DAILY_RESET)
                serviceScope.launch { checkMutex.withLock { performDailyReset() } }
        }
    }
    private lateinit var usageRepo: AppUsageRepository
    private lateinit var limitRepo: LimitRepository
    private var checkInterval: Long = FocusConstants.DEFAULT_CHECK_INTERVAL
    private var monitorJob: Job? = null

    private val sentWarnings = mutableMapOf<String, MutableSet<Int>>()
    private val lockedPackages = mutableSetOf<String>()
    private val taskUnlockedPackages = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as MyPlanApp
        usageRepo = AppUsageRepository(applicationContext, app.focusDb)
        limitRepo = LimitRepository(app.focusDb)
        ContextCompat.registerReceiver(this, resetReceiver, IntentFilter(FocusConstants.ACTION_DAILY_RESET), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_CHECK_NOW -> serviceScope.launch { checkUsage() }
            ACTION_DAILY_RESET -> serviceScope.launch { checkMutex.withLock { performDailyReset() } }
            ACTION_UNLOCK_ALL -> serviceScope.launch { checkMutex.withLock { unlockAllApps() } }
            ACTION_UNLOCK_APP -> {
                val pkg = intent.getStringExtra("packageName") ?: return@onStartCommand START_STICKY
                serviceScope.launch { checkMutex.withLock { unlockSingleApp(pkg) } }
            }
            // Rewards require a unique completed task; arbitrary credit increments are rejected.
        }

        if (intent == null || intent.action == null) {
            startMonitoring()
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        startForeground(FocusConstants.NOTIF_MONITOR_ID, createMonitorNotification())
        getSharedPreferences("myplan_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(FocusConstants.KEY_MONITORING_ENABLED, true).apply()

        serviceScope.launch {
          checkMutex.withLock {
            val locked = usageRepo.getLockedAppsForToday()
            lockedPackages.clear()
            lockedPackages.addAll(locked.map { it.packageName })
            taskUnlockedPackages.clear()
            taskUnlockedPackages.addAll(usageRepo.getUnlockedAppsForToday())
          }
        }

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                checkUsage()
                delay(checkInterval * 1000)
            }
        }
    }

    private fun stopMonitoring() {
        getSharedPreferences("myplan_settings", Context.MODE_PRIVATE).edit()
            .putBoolean(FocusConstants.KEY_MONITORING_ENABLED, false).apply()
        monitorJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    suspend fun checkUsage() = checkMutex.withLock {
        DailyRollover.ensureToday(this)
        if (monitoringDate != java.time.LocalDate.now().toString()) performDailyReset()
        usageRepo.querySystemUsage().forEach { (pkg, millis) ->
            usageRepo.updateUsage(pkg, usageRepo.getAppName(pkg), millis)
        }
        val desired = limitRepo.reconcileLocks(usageRepo.getToday())
        val removed = lockedPackages - desired
        val added = desired - lockedPackages
        lockedPackages.clear()
        lockedPackages.addAll(desired)
        taskUnlockedPackages.clear()
        taskUnlockedPackages.addAll(usageRepo.getUnlockedAppsForToday())
        removed.forEach { pkg ->
            sendBroadcast(Intent(FocusConstants.ACTION_USAGE_UPDATED).setPackage(packageName)
                .putExtra("unlock_single", true).putExtra("packageName", pkg))
        }
        added.forEach { pkg ->
            sendLockBroadcast(pkg)
            sentWarnings.remove(pkg)
            showNotification("时间到!", "「${usageRepo.getAppName(pkg)}」今日使用限额已到，已锁定")
        }
        val byPackage = limitRepo.getAllAppLimitsSync().groupBy { it.packageName }
        val usage = (applicationContext as MyPlanApp).focusDb.usageLogDao().getUsageForDateSync(usageRepo.getToday())
        usage.filter { it.packageName !in desired && !it.isUnlocked }.forEach { row ->
            byPackage[row.packageName].orEmpty().filter { it.dailyLimitMillis > 0 }.forEach { rule ->
                checkLimit(row.packageName, row.appName, row.totalTimeMillis, rule.dailyLimitMillis)
            }
        }
    }
    private suspend fun checkLimit(
        packageName: String, appName: String, usedMillis: Long, limitMillis: Long
    ) {
        if (taskUnlockedPackages.contains(packageName)) return

        val remaining = limitMillis - usedMillis

        when {
            remaining <= 0 -> Unit
            remaining <= TimeFormatter.toMillis(0, FocusConstants.WARNING_1_MIN) ->
                sendWarningIfNeeded(packageName, appName, FocusConstants.WARNING_1_MIN, remaining)
            remaining <= TimeFormatter.toMillis(0, FocusConstants.WARNING_5_MIN) ->
                sendWarningIfNeeded(packageName, appName, FocusConstants.WARNING_5_MIN, remaining)
            remaining <= TimeFormatter.toMillis(0, FocusConstants.WARNING_15_MIN) ->
                sendWarningIfNeeded(packageName, appName, FocusConstants.WARNING_15_MIN, remaining)
        }
    }

    private fun sendWarningIfNeeded(packageName: String, appName: String, threshold: Int, remaining: Long) {
        val warnings = sentWarnings.getOrPut(packageName) { mutableSetOf() }
        if (warnings.contains(threshold)) return
        warnings.add(threshold)

        val remainingStr = TimeFormatter.formatRemaining(remaining)
        val message = when (threshold) {
            FocusConstants.WARNING_15_MIN -> "「$appName」还剩 $remainingStr"
            FocusConstants.WARNING_5_MIN -> "「$appName」还剩 $remainingStr，该收尾了"
            FocusConstants.WARNING_1_MIN -> "「$appName」仅剩 $remainingStr!"
            else -> return
        }
        showNotification("⏰ 使用提醒", message)
    }

    private fun sendLockBroadcast(packageName: String) {
        val intent = Intent(FocusConstants.ACTION_USAGE_UPDATED).apply {
            putExtra("locked", true)
            putExtra("packageName", packageName)
        }
        sendBroadcast(intent.setPackage(this.packageName))
    }

    private suspend fun performDailyReset() {
        monitoringDate = java.time.LocalDate.now().toString()
        lockedPackages.clear()
        taskUnlockedPackages.clear()
        MyPlanApp.instance.creditRepository.refreshDate()
        sentWarnings.clear()
        limitRepo.ensurePresetCategories()
        usageRepo.cleanup()
    }

    private suspend fun unlockAllApps() {
        taskUnlockedPackages.addAll(lockedPackages)
        lockedPackages.clear()
        usageRepo.unlockAllForToday()
        val intent = Intent(FocusConstants.ACTION_USAGE_UPDATED).apply { putExtra("unlocked", true) }
        sendBroadcast(intent.setPackage(this.packageName))
    }

    private suspend fun unlockSingleApp(packageName: String) {
        lockedPackages.remove(packageName)
        taskUnlockedPackages.add(packageName)
        usageRepo.unlockAppForToday(packageName)
        val intent = Intent(FocusConstants.ACTION_USAGE_UPDATED).apply {
            putExtra("unlock_single", true)
            putExtra("packageName", packageName)
        }
        sendBroadcast(intent.setPackage(this.packageName))
    }

    private fun createMonitorNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, MyPlanApp.CHANNEL_FOCUS_MONITOR)
            .setContentTitle("MY PLAN 专注监控中")
            .setContentText("正在追踪应用使用时长")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showNotification(title: String, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, MyPlanApp.CHANNEL_FOCUS_WARNING)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val id = FocusConstants.NOTIF_WARNING_ID_BASE + (System.currentTimeMillis() % 1000).toInt()
        manager.notify(id, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(resetReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopMonitoring()
    }

    companion object {
        const val ACTION_START = "com.example.myplan.START_MONITORING"
        const val ACTION_STOP = "com.example.myplan.STOP_MONITORING"
        const val ACTION_CHECK_NOW = "com.example.myplan.CHECK_NOW"
        const val ACTION_DAILY_RESET = "com.example.myplan.DAILY_RESET"
        const val ACTION_UNLOCK_ALL = "com.example.myplan.UNLOCK_ALL"
        const val ACTION_UNLOCK_APP = "com.example.myplan.UNLOCK_APP"

        fun start(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun checkNow(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply { action = ACTION_CHECK_NOW }
            context.startService(intent)
        }

        fun dailyReset(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply { action = ACTION_DAILY_RESET }
            context.startService(intent)
        }

        fun unlockAll(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply { action = ACTION_UNLOCK_ALL }
            context.startService(intent)
        }

        fun unlockApp(context: Context, packageName: String) {
            val intent = Intent(context, UsageMonitorService::class.java).apply {
                action = ACTION_UNLOCK_APP
                putExtra("packageName", packageName)
            }
            context.startService(intent)
        }

    }
}
