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
        AlarmDiagnostics.record(context, "alarm_received", planId, reminderId, intent.getLongExtra("scheduled_at", 0))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = PlanRepository(PlanDatabase.getInstance(context))
                PlanActions(repo, AlarmScheduler(context)).fire(planId, reminderId,
                    intent.getLongExtra("schedule_version", 0)) { plan ->
                    NotificationHelper.showReminderNotification(context, plan.id, plan.name, plan.description)
                }
            } catch (e: Exception) { AlarmDiagnostics.record(context, "alarm_failed_${e.javaClass.simpleName}", planId, reminderId); Log.e("AlarmReceiver", "Reminder failed", e) }
            finally { pending.finish() }
        }
    }
}
