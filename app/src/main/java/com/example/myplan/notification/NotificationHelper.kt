package com.example.myplan.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.myplan.MainActivity

object NotificationHelper {

    const val CHANNEL_ID = "plan_reminder"
    const val CHANNEL_NAME = "计划提醒"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "计划提醒通知"
            enableVibration(true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun showReminderNotification(
        context: Context,
        planId: Long,
        planName: String,
        planDescription: String?
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("plan_id", planId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, planId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = planDescription?.takeIf { it.isNotBlank() }
            ?: "该执行计划了！"

        val vibrationMode = com.example.myplan.data.SettingsManager.getVibrationMode(context)
        val vibratePattern = when (vibrationMode) { "长震动" -> longArrayOf(0,500,200,500); "短震动" -> longArrayOf(0,200,100,200); else -> longArrayOf(0) }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.myplan.R.mipmap.ic_launcher)
            .setContentTitle(planName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(vibratePattern)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(planId.toInt(), notification)
    }
}
