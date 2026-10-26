package com.example.myplan.quickadd

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.myplan.data.SettingsManager
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

object QuickCaptureNotifications {
    private const val CHANNEL = "quick_capture"
    private const val TAG = "quick_capture"
    private const val ENTRY = 1
    private const val FEEDBACK = 2

    fun refreshEntry(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "通知栏快记", NotificationManager.IMPORTANCE_LOW).apply {
            description = "直接输入一句话创建计划"; setSound(null, null); enableVibration(false)
        })
        if (!SettingsManager.isQuickCaptureEnabled(context)) {
            manager.cancel(TAG, ENTRY); manager.cancel(TAG, FEEDBACK); return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val store = QuickCaptureStore(context)
        store.prune()
        val token = UUID.randomUUID().toString()
        store.put(CaptureRecord(token, QuickDraft(""), "entry"))
        val reply = NotificationCompat.Action.Builder(0, "记一件事", broadcast(context, token, "INPUT", mutable = true))
            .addRemoteInput(RemoteInput.Builder("text").setLabel("例如：明天晚上六点取快递").build())
            .setAllowGeneratedReplies(false).setAuthenticationRequired(true).build()
        post(context, ENTRY, base(context).setContentTitle("随手记一件事")
            .setContentText("输入一句话，到点提醒你")
            .setOngoing(true).setContentIntent(editor(context, token)).addAction(reply).build())
    }

    fun showDraft(context: Context, record: CaptureRecord) {
        val b = base(context).setContentTitle(record.draft.title.ifBlank { "补充快记" })
            .setContentText(record.draft.issue ?: "请确认提醒时间")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${record.draft.issue}\n点这里自选日期和时间"))
            .setContentIntent(editor(context, record.token)).setAutoCancel(false)
        timeChoices(record.draft, Instant.ofEpochMilli(record.updatedAt).atZone(ZoneId.systemDefault())).take(2).forEach { (label, at) ->
            b.addAction(0, label, broadcast(context, record.token, "CHOOSE", at = at))
        }
        b.addAction(0, "自选时间", editor(context, record.token))
        post(context, FEEDBACK, b.build())
    }

    /** Only safe choices for missing/ambiguous times; invalid dates never get guessed. */
    fun timeChoices(draft: QuickDraft, now: ZonedDateTime = ZonedDateTime.now()): List<Pair<String, Long>> {
        if (draft.title.isBlank() || draft.repeatMode != "ONCE") return emptyList()
        if (draft.alternatives.isNotEmpty()) return draft.alternatives.filter { it > now.toInstant().toEpochMilli() }
            .map { formatTime(it) to it }
        if (draft.issue != "还没有提醒时间，请选一个时间") return emptyList()
        val date = draft.date?.let { LocalDate.parse(it) }
        val options = if (date != null) listOf(9, 20).mapNotNull { h -> QuickAddParser.resolve(date, LocalTime.of(h, 0), now.zone) }
        else listOf(now.plusMinutes(10), now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone))
        return options.filter { it.isAfter(now) }.map { formatTime(it.toInstant().toEpochMilli()) to it.toInstant().toEpochMilli() }
    }

    fun showSaved(context: Context, record: CaptureRecord, scheduled: Boolean = true) {
        val d = record.draft
        val time = if (d.repeatMode == "ONCE") d.at?.let(::formatTime).orEmpty()
        else "${when (d.repeatMode) { "EVERY_DAY" -> "每天"; "WEEKDAYS" -> "周一至周五"; else -> "每周 ${d.customDays}" }} " +
            d.reminderTimes.ifEmpty { listOf("%02d:%02d".format(d.hour, d.minute)) }.joinToString("、")
        val b = base(context).setContentTitle(if (scheduled) "已安排：${d.title}" else "已保存，提醒待授权")
            .setContentText(time + (d.focusMinutes?.let { " · 专注 $it 分钟" } ?: "") +
                if (!scheduled) " · 点此检查闹钟权限" else " · 可撤销或修改")
            .setContentIntent(editor(context, record.token)).setAutoCancel(true).setTimeoutAfter(10 * 60_000L)
            .addAction(0, "撤销", broadcast(context, record.token, "UNDO"))
            .addAction(0, if (scheduled) "修改" else "检查权限", editor(context, record.token))
        post(context, FEEDBACK, b.build())
    }

    fun showError(context: Context, token: String, message: String) {
        post(context, FEEDBACK, base(context).setContentTitle("快记需要确认").setContentText(message)
            .setContentIntent(editor(context, token)).addAction(0, "打开快记", editor(context, token)).build())
    }

    fun clearFeedback(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(TAG, FEEDBACK) }

    fun formatTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_input_add).setOnlyAlertOnce(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun post(context: Context, id: Int, notification: Notification) {
        try { context.getSystemService(NotificationManager::class.java).notify(TAG, id, notification) }
        catch (_: SecurityException) { /* User may revoke notifications while a reply is being saved. */ }
    }

    private fun editor(context: Context, token: String) = PendingIntent.getActivity(context, 0,
        Intent(context, QuickCaptureActivity::class.java).setData(Uri.parse("myplan://quick/$token"))
            .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun broadcast(context: Context, token: String, action: String, mutable: Boolean = false, at: Long = 0) =
        PendingIntent.getBroadcast(context, 0, Intent(context, QuickCaptureReceiver::class.java)
            .setAction("quick.$action").setData(Uri.parse("myplan://quick/$token/$action/$at"))
            .putExtra("token", token).putExtra("at", at), PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
}
