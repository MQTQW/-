package com.example.myplan.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.content.ContextCompat
import com.example.myplan.data.SettingsManager
import java.util.UUID

/** Runs only while a reminder rings. Audio continues after the receiver has returned. */
class AlarmPlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var sound: AlarmSoundPlayer? = null
    private var vibrator: Vibrator? = null
    private var planId = 0L
    private var title = ""
    private var description: String? = null
    private var token: String? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { finishRinging(keepNotification = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (intent.getStringExtra(EXTRA_TOKEN) == token) finishRinging(keepNotification = false)
            else if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_PLAY) { if (!foreground) stopSelf(); return START_NOT_STICKY }
        val nextId = intent.getLongExtra("plan_id", 0)
        if (foreground && nextId != planId) {
            stopForeground(STOP_FOREGROUND_DETACH)
            NotificationHelper.postDelivered(this, planId, title, description)
        }
        releaseAudio()
        planId = nextId
        title = intent.getStringExtra("plan_name") ?: "计划提醒"
        description = intent.getStringExtra("plan_desc")
        token = intent.getStringExtra(EXTRA_TOKEN) ?: UUID.randomUUID().toString()
        activePlanId = planId; activeToken = token
        val bypass = AlarmAudioStatus.needsChannelBypass(this)
        val channel = if (bypass) NotificationHelper.createChannel(this) else NotificationHelper.createPlaybackChannel(this)
        val notification = NotificationHelper.buildAlarmNotification(this, planId, title,
            AlarmAudioStatus.warning(this) ?: description ?: "提醒时间到了，可点「停止响铃」关闭", channel, token!!, bypass)
        startForeground(notificationId(planId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        foreground = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyPlan:AlarmPlayback").apply {
            acquire(RING_DURATION + 10_000)
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, intent.getLongExtra("duration", RING_DURATION).coerceIn(1_000, RING_DURATION))
        if (!bypass) {
            sound = AlarmSoundPlayer(this) { finishRinging(keepNotification = true, failed = true) }
            sound?.start(SettingsManager.getAlarmRingtone(this))
            val pattern = when (SettingsManager.getVibrationMode(this)) {
                "off", "关闭" -> null
                "short", "短震动" -> longArrayOf(0, 200, 100, 200, 1_500)
                else -> longArrayOf(0, 500, 200, 500, 1_500)
            }
            if (pattern != null && foreground) {
                vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0), AlarmSoundPlayer.ATTRIBUTES)
            }
        }
        return START_NOT_STICKY
    }

    private fun releaseAudio() {
        sound?.stop(); sound = null
        vibrator?.cancel(); vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun finishRinging(keepNotification: Boolean, failed: Boolean = false) {
        handler.removeCallbacks(timeout)
        releaseAudio()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            if (keepNotification) NotificationHelper.postDelivered(this, planId, title,
                if (failed) "声音播放未完成，请检查闹钟音量和铃声设置" else description)
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout); releaseAudio()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (activeToken == token) { activeToken = null; activePlanId = null }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "stop_alarm_token"
        private const val ACTION_PLAY = "com.example.myplan.PLAY_ALARM"
        private const val ACTION_STOP = "com.example.myplan.STOP_ALARM"
        const val RING_DURATION = 60_000L
        @Volatile private var activePlanId: Long? = null
        @Volatile private var activeToken: String? = null

        fun notificationId(id: Long): Int = if (id > 0) id.toInt() else Int.MAX_VALUE - 100

        fun start(context: Context, planId: Long, name: String, description: String?, duration: Long = RING_DURATION) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra("plan_id", planId); putExtra("plan_name", name); putExtra("plan_desc", description)
                putExtra("duration", duration); putExtra(EXTRA_TOKEN, UUID.randomUUID().toString())
            })
        }

        fun stopIntent(context: Context, token: String): PendingIntent = PendingIntent.getService(context, 0,
            Intent(context, AlarmPlaybackService::class.java).setAction(ACTION_STOP)
                .setData(Uri.parse("myplan://stop-alarm/$token")).putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun dismiss(context: Context, token: String?) {
            if (token != null && activeToken == token) context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }

        fun stopForPlan(context: Context, id: Long) {
            if (activePlanId == id) context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }
    }
}
