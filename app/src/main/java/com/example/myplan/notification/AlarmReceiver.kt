package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import kotlinx.coroutines.*

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra("reminder_id", -1)
        val planId = intent.getLongExtra("plan_id", -1)
        if (reminderId < 0 || planId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = PlanRepository(PlanDatabase.getInstance(context))
                PlanActions(repo, AlarmScheduler(context)).fire(planId, reminderId) { plan ->
                    NotificationHelper.showReminderNotification(context, plan.id, plan.name, plan.description)
                }
            } catch (e: Exception) { Log.e("AlarmReceiver", "Reminder failed", e) }
            finally { pending.finish() }
        }
    }
}
