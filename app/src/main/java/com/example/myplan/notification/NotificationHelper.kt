package com.example.myplan.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myplan.MainActivity
import com.example.myplan.data.SettingsManager
import java.security.MessageDigest

object NotificationHelper {
    const val CHANNEL_ID = "plan_reminder"
    const val CHANNEL_NAME = "计划提醒"

    fun createChannel(context: Context): String {
        val alarm = SettingsManager.getReminderMode(context) == "alarm"
        val sound = if (alarm) SettingsManager.getAlarmRingtone(context) else SettingsManager.getNotificationRingtone(context)
        val vibration = SettingsManager.getVibrationMode(context)
        val signature = "$alarm|$sound|$vibration"
        val suffix = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val id = "${CHANNEL_ID}_$suffix"
        val uri = when (sound) {
            "silent" -> null
            "default" -> RingtoneManager.getDefaultUri(if (alarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            else -> Uri.parse(sound)
        }
        val pattern = when (vibration) {
            "off", "关闭" -> null
            "short", "短震动" -> longArrayOf(0, 200, 100, 200)
            else -> longArrayOf(0, 500, 200, 500)
        }
        val channel = NotificationChannel(id, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "计划提醒通知"
            setSound(uri, AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(pattern != null)
            if (pattern != null) vibrationPattern = pattern
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return id
    }

    fun showReminderNotification(context: Context, planId: Long, planName: String, planDescription: String?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("plan_id", planId)
        }
        val pi = PendingIntent.getActivity(context, planId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = planDescription?.takeIf { it.isNotBlank() } ?: "该执行计划了！"
        val notification = NotificationCompat.Builder(context, createChannel(context))
            .setSmallIcon(com.example.myplan.R.drawable.ic_schedule)
            .setContentTitle(planName).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi).setAutoCancel(true).build()
        if (SettingsManager.getReminderMode(context) == "alarm") {
            notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        }
        context.getSystemService(NotificationManager::class.java).notify(planId.toInt(), notification)
    }
}
