package com.example.myplan.notification

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import com.example.myplan.BuildConfig
import com.example.myplan.data.SettingsManager
import com.example.myplan.focus.util.PermissionHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bounded local troubleshooting record; never stores titles, descriptions, URIs or keys. */
object AlarmDiagnostics {
    private const val PREFS = "alarm_diagnostics"
    private const val LIMIT = 160

    @Synchronized
    fun record(context: Context, stage: String, planId: Long = 0, reminderId: Long = 0, scheduledAt: Long = 0) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val history = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrDefault(JSONArray())
            val kept = JSONArray()
            for (i in (history.length() - LIMIT + 1).coerceAtLeast(0) until history.length()) kept.put(history.getJSONObject(i))
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            val power = context.getSystemService(PowerManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            kept.put(JSONObject().put("at", System.currentTimeMillis()).put("elapsed", SystemClock.elapsedRealtime())
                .put("pid", Process.myPid()).put("stage", stage).put("plan", planId).put("reminder", reminderId)
                .put("scheduledAt", scheduledAt).put("importance", info.importance)
                .put("interactive", power.isInteractive).put("idle", power.isDeviceIdleMode).put("powerSave", power.isPowerSaveMode)
                .put("alarmVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM)).put("audioMode", audio.mode))
            prefs.edit().putString("events", kept.toString()).apply()
        }
    }

    fun report(context: Context): String = buildString {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        appendLine("MY PLAN 响铃排查记录")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("系统版本：${Build.DISPLAY}")
        appendLine("通知允许：${notifications.areNotificationsEnabled()}")
        appendLine("准确提醒允许：${AlarmScheduler(context).canScheduleExactAlarms()}")
        appendLine("忽略电池优化：${PermissionHelper.isIgnoringBatteryOptimizations(context)}")
        appendLine("系统后台限制：${context.getSystemService(ActivityManager::class.java).isBackgroundRestricted}")
        appendLine("提醒方式：${SettingsManager.getReminderMode(context)}")
        appendLine("铃声选择：${when (SettingsManager.getAlarmRingtone(context)) { "silent" -> "静音"; "default" -> "系统默认"; else -> "自选" }}")
        appendLine("闹钟音量：${audio.getStreamVolume(AudioManager.STREAM_ALARM)}/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        appendLine("铃声模式：${audio.ringerMode}；音频模式：${audio.mode}；勿扰过滤：${notifications.currentInterruptionFilter}")
        appendLine("提醒状态：${AlarmAudioStatus.warning(context) ?: "未检测到静音限制"}")
        appendLine("厂商自启动和后台音频规则无法全部自动读取。")
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            appendLine("\n最近进程退出（仅系统原因码，不含异常正文）：")
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 3).forEach {
                appendLine("${formatTime(it.timestamp)} pid=${it.pid} reason=${it.reason} status=${it.status} importance=${it.importance}")
            }
        }
        appendLine("\n最近触发链路（不含计划正文、账号和密钥）：")
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]")
        val events = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        if (events.length() == 0) appendLine("暂无记录，可先安排一次测试提醒。")
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val at = formatTime(event.getLong("at"))
            append("$at ${event.getString("stage")} plan=${event.optLong("plan")} rem=${event.optLong("reminder")}")
            append(" pid=${event.optInt("pid")} importance=${event.optInt("importance")}")
            if (event.optLong("scheduledAt") > 0) {
                append(" due=${formatTime(event.getLong("scheduledAt"))}")
                if (event.getString("stage").contains("received")) append(" delayMs=${event.getLong("at") - event.getLong("scheduledAt")}")
            }
            if (event.has("interactive")) append(" interactive=${event.optBoolean("interactive")} idle=${event.optBoolean("idle")} powerSave=${event.optBoolean("powerSave")} alarmVolume=${event.optInt("alarmVolume")} audioMode=${event.optInt("audioMode")}")
            appendLine()
        }
        appendLine("\naudio_started 表示播放器已接受开始播放调用，不能单凭它证明扬声器已经出声。")
    }

    private fun formatTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
}
