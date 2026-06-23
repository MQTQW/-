package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import com.example.myplan.data.SettingsManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getLongExtra("plan_id", -1)
        val reminderId = intent.getLongExtra("reminder_id", -1)
        val planName = intent.getStringExtra("plan_name") ?: return
        val planDesc = intent.getStringExtra("plan_desc")
        val repeatMode = intent.getStringExtra("repeat_mode") ?: "EVERY_DAY"
        val customDaysStr = intent.getStringExtra("custom_days") ?: ""
        val hour = intent.getIntExtra("hour", -1)
        val minute = intent.getIntExtra("minute", -1)

        NotificationHelper.showReminderNotification(context, planId, planName, planDesc)

        val reminderMode = SettingsManager.getReminderMode(context)
        if (reminderMode == "alarm") {
            val ringtoneStr = SettingsManager.getAlarmRingtone(context)
            val uri = if (ringtoneStr != "default") Uri.parse(ringtoneStr)
                      else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            try {
                RingtoneManager.getRingtone(context, uri)?.play()
            } catch (_: Exception) {}
        }

        if (reminderId >= 0 && hour >= 0 && minute >= 0) {
            try {
                val scheduler = AlarmScheduler(context)
                val customDays = customDaysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                val nextTime = scheduler.computeNextFireTime(hour, minute, repeatMode, customDays)
                if (nextTime != null) {
                    scheduler.scheduleAlarm(
                        planId = planId, reminderId = reminderId, triggerTimeMillis = nextTime,
                        planName = planName, planDescription = planDesc,
                        repeatMode = repeatMode, customDays = customDaysStr,
                        hour = hour, minute = minute,
                    )
                }
            } catch (_: Exception) {}
        }
    }
}
