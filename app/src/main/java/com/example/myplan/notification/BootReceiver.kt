package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
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
                        val triggerTime = if (reminder.time <= System.currentTimeMillis()) {
                            // 已过期 → 按重复模式推到未来
                            when (reminder.repeatMode) {
                                "DAILY" -> {
                                    val daysPassed = ((System.currentTimeMillis() - reminder.time) / 86_400_000L) + 1
                                    reminder.time + daysPassed * 86_400_000L
                                }
                                "WEEKLY" -> {
                                    val weeksPassed = ((System.currentTimeMillis() - reminder.time) / 604_800_000L) + 1
                                    reminder.time + weeksPassed * 604_800_000L
                                }
                                else -> return@forEach  // NONE: 跳过
                            }
                        } else {
                            reminder.time
                        }
                        scheduler.scheduleAlarm(plan.id, reminder.id, triggerTime)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
