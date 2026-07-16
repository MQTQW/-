package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.service.UsageMonitorService
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = PlanDatabase.getInstance(context)
                val repo = PlanRepository(db)
                val reminders = repo.getAllEnabledReminders()
                val scheduler = AlarmScheduler(context)

                reminders.forEach { reminder ->
                    val plan = repo.getPlanById(reminder.planId)
                    if (plan != null && plan.isEnabled) {
                        val customDays = plan.customDays.split(",")
                            .mapNotNull { it.trim().toIntOrNull() }.toSet()
                        val nextTime = scheduler.computeNextFireTime(
                            hour = reminder.hour,
                            minute = reminder.minute,
                            repeatMode = plan.repeatMode,
                            customDays = customDays
                        )
                        if (nextTime != null) {
                            scheduler.scheduleAlarm(
                                planId = plan.id, reminderId = reminder.id, triggerTimeMillis = nextTime,
                                planName = plan.name, planDescription = plan.description,
                                repeatMode = plan.repeatMode, customDays = plan.customDays,
                                hour = reminder.hour, minute = reminder.minute,
                            )
                        }
                    }
                }
                // Restart FocusFlow service on boot
                try { UsageMonitorService.start(context) } catch (_: Exception) {}
            } finally {
                pendingResult.finish()
            }
        }
    }
}
