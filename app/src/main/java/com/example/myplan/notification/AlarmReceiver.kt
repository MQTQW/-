package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import kotlinx.coroutines.*

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getLongExtra("plan_id", -1)
        val reminderId = intent.getLongExtra("reminder_id", -1)
        if (planId == -1L || reminderId == -1L) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = PlanDatabase.getInstance(context)
                val repo = PlanRepository(db)
                val plan = repo.getPlanById(planId)
                val reminder = repo.getReminderById(reminderId)

                if (plan != null && plan.isEnabled && reminder != null && reminder.isEnabled) {
                    // 发送通知
                    NotificationHelper.showReminderNotification(
                        context = context,
                        planId = plan.id,
                        planName = plan.name,
                        planDescription = plan.description
                    )

                    // 处理重复模式
                    when (reminder.repeatMode) {
                        "DAILY" -> {
                            val nextTime = reminder.time + 86_400_000L
                            repo.updateReminder(reminder.copy(time = nextTime))
                            AlarmScheduler(context).scheduleAlarm(plan.id, reminder.id, nextTime)
                        }
                        "WEEKLY" -> {
                            val nextTime = reminder.time + 604_800_000L
                            repo.updateReminder(reminder.copy(time = nextTime))
                            AlarmScheduler(context).scheduleAlarm(plan.id, reminder.id, nextTime)
                        }
                        else -> {
                            // NONE: 禁用该提醒
                            repo.updateReminder(reminder.copy(isEnabled = false))
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
