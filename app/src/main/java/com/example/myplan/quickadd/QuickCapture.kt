package com.example.myplan.quickadd

import android.content.Context
import com.example.myplan.data.SettingsManager
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

object QuickCapture {
    private val mutex = Mutex()

    suspend fun receive(context: Context, token: String, text: String) = mutex.withLock {
        if (!SettingsManager.isQuickCaptureEnabled(context)) return@withLock
        val store = QuickCaptureStore(context)
        val record = store.get(token) ?: return@withLock
        if (record.status == "cancelled") return@withLock
        val parsed = if (record.status == "entry") {
            CaptureRecord(token, QuickAddParser.parse(text)).also { store.put(it) }
        } else record
        saveOrAsk(context, parsed)
        QuickCaptureNotifications.refreshEntry(context)
    }

    suspend fun choose(context: Context, token: String, at: Long) = mutex.withLock {
        val record = QuickCaptureStore(context).get(token) ?: return@withLock
        if (record.status != "pending") return@withLock
        val offered = QuickCaptureNotifications.timeChoices(record.draft,
            Instant.ofEpochMilli(record.updatedAt).atZone(ZoneId.systemDefault())).map { it.second }
        if (at !in offered || at <= System.currentTimeMillis()) {
            QuickCaptureNotifications.showDraft(context, record.copy(draft = record.draft.copy(issue = "这个时间已经过去，请重新选择")))
            return@withLock
        }
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val draft = record.draft.copy(at = at, hour = time.hour, minute = time.minute,
            date = time.toLocalDate().toString(), issue = null, alternatives = emptyList())
        saveOrAsk(context, record.copy(draft = draft))
    }

    suspend fun saveEditor(context: Context, token: String, draft: QuickDraft): Long = mutex.withLock {
        val record = QuickCaptureStore(context).get(token) ?: error("这条快记已过期，请重新记录")
        check(record.status != "cancelled") { "这条快记已取消" }
        save(context, record.copy(draft = draft, status = "pending"), editing = true)
    }

    suspend fun undo(context: Context, token: String) = mutex.withLock {
        val store = QuickCaptureStore(context)
        val record = store.get(token) ?: return@withLock
        if (record.status == "cancelled") return@withLock
        if (record.planId != 0L) {
            if (System.currentTimeMillis() - record.updatedAt > 10 * 60_000) return@withLock
            val repo = PlanRepository(PlanDatabase.getInstance(context))
            repo.getByCreationToken(token)?.let { PlanActions(repo, AlarmScheduler(context)).delete(it) }
        }
        // Tombstone prevents old actions from recreating a cancelled reminder.
        store.put(record.copy(status = "cancelled"))
        QuickCaptureNotifications.clearFeedback(context)
    }

    private suspend fun saveOrAsk(context: Context, record: CaptureRecord) {
        if (record.status == "saved") {
            val repo = PlanRepository(PlanDatabase.getInstance(context))
            if (repo.getByCreationToken(record.token) == null) return
            val scheduled = try { PlanActions(repo, AlarmScheduler(context)).sync(record.planId); true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
            QuickCaptureNotifications.showSaved(context, record, scheduled)
            return
        }
        if (record.draft.issue != null || record.draft.title.isBlank()) {
            QuickCaptureStore(context).put(record)
            QuickCaptureNotifications.showDraft(context, record)
        } else save(context, record)
    }

    private suspend fun save(context: Context, record: CaptureRecord, editing: Boolean = false): Long {
        val d = record.draft
        require(d.title.isNotBlank() && d.title.length <= 200) { "请输入 1～200 字的计划名称" }
        require(d.hour in 0..23 && d.minute in 0..59) { "请选择有效时间" }
        require(d.repeatMode in setOf("ONCE", "EVERY_DAY", "WEEKDAYS", "CUSTOM")) { "请选择有效的重复方式" }
        require(d.focusMinutes == null || d.focusMinutes in setOf(15, 25, 45)) { "请选择 15、25 或 45 分钟专注" }
        val times = d.reminderTimes.ifEmpty { listOf("%02d:%02d".format(d.hour, d.minute)) }
        require(times.size in 1..8 && times.distinct().size == times.size &&
            times.all { Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) }) { "请选择有效且不重复的提醒时间" }
        if (d.repeatMode == "CUSTOM") require(d.customDays.split(',').mapNotNull(String::toIntOrNull).let { days ->
            days.isNotEmpty() && days.distinct().size == days.size && days.all { it in 1..7 }
        }) { "请至少选择一个有效星期" }
        if (d.category != null) require(d.category in setOf("日常", "工作", "学习", "健康", "紧急", "其他")) { "请选择有效的分类" }
        if (d.repeatMode == "ONCE") require((d.at ?: 0) > System.currentTimeMillis()) { "请选择未来的提醒时间" }
        if (d.repeatMode == "ONCE") require(times.size == 1 && Instant.ofEpochMilli(d.at!!).atZone(ZoneId.systemDefault()).toLocalTime()
            .withSecond(0).withNano(0) == LocalTime.parse(times.single())) { "一次性提醒日期与时间不一致" }
        val repo = PlanRepository(PlanDatabase.getInstance(context))
        val existing = repo.getByCreationToken(record.token)
        check(record.planId == 0L || existing != null) { "这个计划已删除，请重新记录" }
        val plan = if (editing && existing != null) existing.copy(name = d.title, repeatMode = d.repeatMode,
            customDays = if (d.repeatMode == "CUSTOM") d.customDays else "", oneTimeAt = d.at,
            category = d.category ?: existing.category, suggestedFocusMinutes = d.focusMinutes ?: existing.suggestedFocusMinutes,
            updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1))
        else PlanEntity(name = d.title, repeatMode = d.repeatMode, customDays = if (d.repeatMode == "CUSTOM") d.customDays else "",
            category = d.category ?: "日常", suggestedFocusMinutes = d.focusMinutes ?: 25,
            oneTimeAt = d.at, creationToken = record.token)
        val existingReminders = if (editing && existing != null) repo.getRemindersByPlanId(existing.id) else emptyList()
        val reminders = times.mapIndexed { index, value ->
            val time = LocalTime.parse(value)
            existingReminders.getOrNull(index)?.copy(hour = time.hour, minute = time.minute)
                ?: ReminderEntity(planId = plan.id, hour = time.hour, minute = time.minute)
        }
        var scheduled = true
        val id = try { PlanActions(repo, AlarmScheduler(context)).save(plan, reminders) }
        catch (e: AlarmSyncException) { scheduled = false; e.planId }
        val saved = record.copy(status = "saved", planId = id, updatedAt = System.currentTimeMillis())
        QuickCaptureStore(context).put(saved)
        QuickCaptureNotifications.showSaved(context, saved, scheduled)
        return id
    }
}
