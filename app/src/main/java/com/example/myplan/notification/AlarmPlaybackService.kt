package com.example.myplan.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.content.ContextCompat
import com.example.myplan.data.SettingsManager
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import kotlinx.coroutines.*
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
    private var preparing = false
    private var pendingDeliveries = 0
    private var latestStartId = 0
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { finishRinging(keepNotification = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            if (intent.getStringExtra(EXTRA_TOKEN) == token) finishRinging(keepNotification = false)
            else if (!foreground && pendingDeliveries == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DELIVER || intent?.action == ACTION_TEST) {
            receiveScheduled(intent, startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_PLAY) { if (!foreground) stopSelf(); return START_NOT_STICKY }
        playAlarm(intent)
        return START_NOT_STICKY
    }

    private fun receiveScheduled(intent: Intent, startId: Int) {
        val id = intent.getLongExtra("plan_id", 0)
        val reminder = intent.getLongExtra("reminder_id", -1)
        AlarmDiagnostics.record(this, "alarm_service_received", id, reminder.coerceAtLeast(0), intent.getLongExtra("scheduled_at", 0))
        try {
            ensurePreparingForeground()
            holdCpu()
        } catch (e: Exception) {
            AlarmDiagnostics.record(this, "delivery_foreground_failed_${e.javaClass.simpleName}", id, reminder)
            if (!foreground) stopSelfResult(startId)
            return
        }
        pendingDeliveries++
        deliveryScope.launch {
            try {
                withTimeout(10_000) {
                    if (intent.action == ACTION_TEST) {
                        deliver(0, "测试提醒", "提醒已送达，请确认声音和震动符合你的设置。")
                    } else if (id > 0 && reminder > 0) {
                        val repo = PlanRepository(PlanDatabase.getInstance(this@AlarmPlaybackService))
                        // Room suspend queries return to Main, so validation and delivery
                        // keep the shared PlanActions lock through the notification/audio handoff.
                        val accepted = PlanActions(repo, AlarmScheduler(this@AlarmPlaybackService)).fire(id, reminder,
                            intent.getLongExtra("schedule_version", 0)) { plan -> deliver(plan.id, plan.name, plan.description) }
                        if (!accepted) AlarmDiagnostics.record(this@AlarmPlaybackService, "alarm_ignored", id, reminder)
                        com.example.myplan.widget.PlanWidgetProvider.requestUpdate(this@AlarmPlaybackService)
                    }
                }
            } catch (e: CancellationException) {
                AlarmDiagnostics.record(this@AlarmPlaybackService, "delivery_cancelled_${e.javaClass.simpleName}", id, reminder)
                throw e
            } catch (e: Exception) {
                AlarmDiagnostics.record(this@AlarmPlaybackService, "delivery_failed_${e.javaClass.simpleName}", id, reminder)
                android.util.Log.e("AlarmPlaybackService", "Scheduled delivery failed", e)
            } finally {
                pendingDeliveries--
                if (!foreground && pendingDeliveries == 0) {
                    if (preparing) { stopForeground(STOP_FOREGROUND_REMOVE); preparing = false }
                    releaseCpu()
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    private fun ensurePreparingForeground() {
        if (foreground || preparing) return
        startForeground(PREPARING_ID, NotificationHelper.preparingNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        preparing = true
        AlarmDiagnostics.record(this, "delivery_foreground_ready")
    }

    private fun deliver(id: Long, name: String, body: String?) {
        AlarmDiagnostics.record(this, "delivery_${SettingsManager.getReminderMode(this)}", id)
        if (SettingsManager.getReminderMode(this) == "alarm" && SettingsManager.getAlarmRingtone(this) != "silent") {
            playAlarm(Intent().putExtra("plan_id", id).putExtra("plan_name", name).putExtra("plan_desc", body))
        } else NotificationHelper.postReminder(this, id, name, body)
    }

    private fun playAlarm(intent: Intent) {
        val nextId = intent.getLongExtra("plan_id", 0)
        if (foreground && nextId != planId) {
            stopForeground(STOP_FOREGROUND_DETACH)
            NotificationHelper.postDelivered(this, planId, title, description)
        }
        releaseAudio()
        planId = nextId
        AlarmDiagnostics.record(this, "service_started", planId)
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
        if (preparing) { getSystemService(NotificationManager::class.java).cancel(PREPARING_ID); preparing = false }
        AlarmDiagnostics.record(this, "foreground_ready", planId)
        holdCpu()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, intent.getLongExtra("duration", RING_DURATION).coerceIn(1_000, RING_DURATION))
        if (!bypass) {
            sound = AlarmSoundPlayer(this, onEvent = { AlarmDiagnostics.record(this, it, planId) }) { finishRinging(keepNotification = true, failed = true) }
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
    }

    private fun holdCpu() {
        releaseCpu()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyPlan:AlarmPlayback").apply {
            acquire(RING_DURATION + 10_000)
        }
    }

    private fun releaseAudio() {
        sound?.stop(); sound = null
        vibrator?.cancel(); vibrator = null
    }

    private fun releaseCpu() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun finishRinging(keepNotification: Boolean, failed: Boolean = false) {
        handler.removeCallbacks(timeout)
        releaseAudio()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            if (keepNotification) NotificationHelper.postDelivered(this, planId, title,
                if (failed) "声音播放未完成，请检查闹钟音量和铃声设置" else description)
        }
        if (pendingDeliveries > 0) ensurePreparingForeground()
        else {
            if (preparing) { stopForeground(STOP_FOREGROUND_REMOVE); preparing = false }
            releaseCpu()
            stopSelf()
        }
    }

    override fun onDestroy() {
        AlarmDiagnostics.record(this, "service_destroyed", planId)
        deliveryScope.cancel()
        handler.removeCallbacks(timeout); releaseAudio(); releaseCpu()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (preparing) { stopForeground(STOP_FOREGROUND_REMOVE); preparing = false }
        if (activeToken == token) { activeToken = null; activePlanId = null }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "stop_alarm_token"
        private const val ACTION_PLAY = "com.example.myplan.PLAY_ALARM"
        private const val ACTION_STOP = "com.example.myplan.STOP_ALARM"
        private const val ACTION_DELIVER = "com.example.myplan.DELIVER_ALARM"
        private const val ACTION_TEST = "com.example.myplan.DELIVER_TEST_ALARM"
        private const val PREPARING_ID = Int.MAX_VALUE - 101
        const val RING_DURATION = 60_000L
        @Volatile private var activePlanId: Long? = null
        @Volatile private var activeToken: String? = null

        fun scheduledIntent(context: Context, reminderId: Long, test: Boolean = false): Intent =
            Intent(context, AlarmPlaybackService::class.java).setAction(if (test) ACTION_TEST else ACTION_DELIVER)
                .setData(Uri.parse(if (test) "myplan://scheduled-test" else "myplan://scheduled-reminder/$reminderId"))

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
