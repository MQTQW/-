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
    private const val PLAYBACK_CHANNEL = "alarm_playback_controls"

    fun createChannel(context: Context): String {
        val alarm = SettingsManager.getReminderMode(context) == "alarm"
        val sound = if (alarm) SettingsManager.getAlarmRingtone(context) else SettingsManager.getNotificationRingtone(context)
        val vibration = SettingsManager.getVibrationMode(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val bypass = alarm && manager.isNotificationPolicyAccessGranted
        val signature = "$alarm|$sound|$vibration|$bypass"
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
            setBypassDnd(bypass)
            setSound(uri, AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(pattern != null)
            if (pattern != null) vibrationPattern = pattern
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return id
    }

    fun showReminderNotification(context: Context, planId: Long, planName: String, planDescription: String?) {
        AlarmDiagnostics.record(context, "delivery_${SettingsManager.getReminderMode(context)}", planId)
        if (SettingsManager.getReminderMode(context) == "alarm" && SettingsManager.getAlarmRingtone(context) != "silent") {
            try {
                AlarmPlaybackService.start(context, planId, planName, planDescription)
                return
            } catch (e: Exception) {
                AlarmDiagnostics.record(context, "service_start_failed_${e.javaClass.simpleName}", planId)
                android.util.Log.e("NotificationHelper", "Alarm playback service could not start", e)
            }
        }
        postReminder(context, planId, planName, planDescription)
    }

    fun postReminder(context: Context, planId: Long, name: String, description: String?) {
        post(context, planId, buildReminderNotification(context, planId, name, description, createChannel(context)))
    }

    fun preparingNotification(context: Context): android.app.Notification = NotificationCompat.Builder(context, createPlaybackChannel(context))
        .setSmallIcon(com.example.myplan.R.drawable.ic_schedule).setContentTitle("正在确认提醒")
        .setContentText("核对计划状态后开始提醒")
        .setCategory(NotificationCompat.CATEGORY_ALARM).setSilent(true).setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    fun createPlaybackChannel(context: Context): String {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PLAYBACK_CHANNEL, "闹铃播放控制", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "闹铃由独立音频播放，此通知用于停止响铃"
                setSound(null, null); enableVibration(false)
            })
        return PLAYBACK_CHANNEL
    }

    private fun openPlan(context: Context, planId: Long, token: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.myplan.OPEN_PLAN"
            data = Uri.parse("myplan://plan/$planId${token?.let { "/$it" }.orEmpty()}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("plan_id", planId)
            putExtra(AlarmPlaybackService.EXTRA_TOKEN, token)
        }
        return PendingIntent.getActivity(context, planId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildReminderNotification(context: Context, planId: Long, planName: String, planDescription: String?, channel: String): android.app.Notification {
        val body = planDescription?.takeIf { it.isNotBlank() } ?: "该执行计划了！"
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.example.myplan.R.drawable.ic_schedule)
            .setContentTitle(planName).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPlan(context, planId)).setAutoCancel(true).build()
    }

    fun buildAlarmNotification(context: Context, planId: Long, title: String, body: String,
        channel: String, token: String, systemSound: Boolean): android.app.Notification {
        val stop = AlarmPlaybackService.stopIntent(context, token)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.example.myplan.R.drawable.ic_schedule).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPlan(context, planId, token)).setDeleteIntent(stop)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "停止响铃", stop)
        if (!systemSound) builder.setSilent(true)
        return builder.build().also {
            if (systemSound) it.flags = it.flags or android.app.Notification.FLAG_INSISTENT
        }
    }

    fun postDelivered(context: Context, planId: Long, title: String, description: String?) {
        post(context, planId, buildReminderNotification(context, planId, title, description, createPlaybackChannel(context)))
    }

    private fun post(context: Context, planId: Long, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).notify(AlarmPlaybackService.notificationId(planId), notification)
    }
}
