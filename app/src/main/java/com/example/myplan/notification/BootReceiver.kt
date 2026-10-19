package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DailyRollover.ensureToday(context, forceSchedule = true)
                val repo = PlanRepository(PlanDatabase.getInstance(context))
                val actions = PlanActions(repo, AlarmScheduler(context))
                actions.restoreAll()
                com.example.myplan.quickadd.QuickCaptureNotifications.refreshEntry(context)
                val enabled = com.example.myplan.data.SettingsManager.getPrefs(context)
                    .getBoolean(com.example.myplan.focus.util.FocusConstants.KEY_MONITORING_ENABLED, false)
                if (enabled && com.example.myplan.focus.util.PermissionHelper.hasUsageStatsPermission(context)) {
                    try { com.example.myplan.focus.service.UsageMonitorService.start(context) }
                    catch (e: Exception) { Log.e("BootReceiver", "Monitor recovery failed", e) }
                }
            } catch (e: Exception) { Log.e("BootReceiver", "Recovery failed", e) }
            finally { pending.finish() }
        }
    }
}
