package com.example.myplan.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.myplan.MainActivity
import com.example.myplan.R

/**
 * 最小化语音助手服务 — 仅验证服务能否启动 + 前台通知能否显示。
 * 不包含 STT/TTS/NLU。后续逐步加回语音功能。
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL_ID = "voice_minimal"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "start() called")
            } catch (e: Exception) {
                Log.e(TAG, "start() crashed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceAssistantService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "语音服务运行中" }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            Log.d(TAG, "Channel created")
        } catch (e: Exception) {
            Log.e(TAG, "Channel failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        try {
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("MY PLAN")
                    .setContentText("语音助手已就绪")
                    .setSmallIcon(R.drawable.ic_schedule)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("MY PLAN")
                    .setContentText("语音助手已就绪")
                    .setSmallIcon(R.drawable.ic_schedule)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN)
                    .build()
            }

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground started successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand crashed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
