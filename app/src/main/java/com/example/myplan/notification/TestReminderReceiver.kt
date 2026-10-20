package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TestReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmDiagnostics.record(context, "alarm_received", scheduledAt = intent.getLongExtra("scheduled_at", 0))
        NotificationHelper.showReminderNotification(context, 0, "测试提醒", "提醒已送达，请确认声音和震动符合你的设置。")
    }
}
