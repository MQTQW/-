package com.example.myplan.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.myplan.data.SettingsManager

object AlarmAudioStatus {
    private fun alarmsAllowed(manager: NotificationManager): Boolean {
        val policy = if (Build.VERSION.SDK_INT >= 30) manager.consolidatedNotificationPolicy else manager.notificationPolicy
        return (policy?.priorityCategories ?: 0) and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
    }

    fun needsChannelBypass(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
            !alarmsAllowed(manager) &&
            manager.getNotificationChannel(NotificationHelper.createChannel(context))?.canBypassDnd() == true
    }

    fun warning(context: Context): String? {
        if (SettingsManager.getAlarmRingtone(context) == "silent") return "闹铃铃声已设为静音"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) return "勿扰设为完全静音，请在系统中允许闹钟"
        if (context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0) return "闹钟音量为 0，请到系统声音设置调高"
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> "勿扰设为完全静音，请在系统中允许闹钟"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                if (!alarmsAllowed(manager) &&
                    !needsChannelBypass(context)) "勿扰尚未允许闹钟，请开启提醒例外" else null
            }
            else -> null
        }
    }
}
