package com.example.myplan.assistant

import com.example.myplan.assistant.api.*
import com.example.myplan.quickadd.QuickAddParser
import com.example.myplan.voice.PlanVoiceData
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

data class DraftResult(val plan: PlanVoiceData? = null, val question: String? = null, val usedAi: Boolean = false)

/** Text and transcribed speech take the same route; only an explicit confirmation can save. */
class PlanDraftWorkflow(private val remoteParse: suspend (String, ZonedDateTime) -> AssistParseResult) {
    suspend fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now(), allowAi: Boolean = true): DraftResult {
        require(text.isNotBlank() && text.length <= 700) { "请用 1～700 字描述一条计划" }
        if (Regex("每月|每年|每隔|隔周|隔天|每(?:两|二|[2-9]|\\d{2,})周").containsMatchIn(text))
            return DraftResult(question = "这类重复周期暂不支持，可选择一次性、每天、工作日或自选星期。")
        val duration = Regex("(?:先)?专注(?:时长)?\\s*([0-9零一二两三四五六七八九十]+)\\s*分钟").find(text)
        val focusMinutes = duration?.groupValues?.get(1)?.let { value ->
            value.toIntOrNull() ?: mapOf("十五" to 15, "二十五" to 25, "四十五" to 45)[value]
        }
        if (duration != null && focusMinutes !in setOf(15, 25, 45))
            return DraftResult(question = "本版支持 15、25 或 45 分钟专注，请调整一下时长。")
        val reminderText = if (duration == null) text else text.removeRange(duration.range).trim(' ', '，', ',', '。')
        val local = QuickAddParser.parse(reminderText, now)
        if (local.issue == null && local.title.isNotBlank()) {
            val plan = PlanVoiceData(name = local.title, repeatMode = local.repeatMode,
                reminderTimes = listOf("%02d:%02d".format(local.hour, local.minute)),
                customDays = local.customDays.split(',').mapNotNull(String::toIntOrNull).toSet(),
                oneTimeAt = local.at, focusMinutes = focusMinutes)
            validate(plan, now)
            return DraftResult(plan = plan)
        }
        if (!allowAi) return DraftResult(question = local.issue ?: "请补充事项和提醒时间。")
        val response = remoteParse(text, now)
        if (!response.question.isNullOrBlank()) return DraftResult(question = response.question, usedAi = true)
        val draft = response.draft ?: throw AssistException(AssistError.INVALID_RESPONSE, "这次未能整理出计划，请补充事项和时间。")
        val once = if (draft.repeatMode == AssistRepeatMode.ONCE) {
            val date = draft.date ?: throw AssistException(AssistError.INVALID_RESPONSE, "请补充提醒日期。")
            val time = draft.reminderTimes.singleOrNull() ?: throw AssistException(AssistError.INVALID_RESPONSE, "一次性提醒请设置一个时间。")
            QuickAddParser.resolve(date, time, now.zone)?.toInstant()?.toEpochMilli()
                ?: throw AssistException(AssistError.INVALID_RESPONSE, "这个时间受夏令时影响，请重新选择。")
        } else null
        val plan = PlanVoiceData(name = draft.title, category = draft.category, repeatMode = draft.repeatMode.name,
            reminderTimes = draft.reminderTimes.map { "%02d:%02d".format(it.hour, it.minute) },
            customDays = draft.customDays.toSet(), oneTimeAt = once,
            focusMinutes = focusMinutes ?: draft.focusMinutes)
        validate(plan, now)
        return DraftResult(plan = plan, usedAi = true)
    }

    companion object {
        fun validate(plan: PlanVoiceData, now: ZonedDateTime = ZonedDateTime.now()) {
            require(plan.name.isNotBlank() && plan.name.length <= 200) { "请填写 1～200 字的计划名称" }
            require(plan.category in setOf("日常", "工作", "学习", "健康", "紧急", "其他")) { "计划分类无效" }
            require(plan.repeatMode in setOf("ONCE", "EVERY_DAY", "WEEKDAYS", "CUSTOM")) { "重复方式暂不支持，请手动选择" }
            require(plan.reminderTimes.size in 1..8 && plan.reminderTimes.distinct().size == plan.reminderTimes.size &&
                plan.reminderTimes.all { Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) }) { "请设置有效且不重复的提醒时间" }
            require(plan.focusMinutes == null || plan.focusMinutes in setOf(15, 25, 45)) { "请选择 15、25 或 45 分钟专注" }
            if (plan.repeatMode == "CUSTOM") require(plan.customDays.isNotEmpty() && plan.customDays.all { it in 1..7 }) { "请至少选择一个星期" }
            if (plan.repeatMode == "ONCE") {
                require(plan.reminderTimes.size == 1 && (plan.oneTimeAt ?: 0) > now.toInstant().toEpochMilli()) { "请选择未来的提醒日期和时间" }
                val time = Instant.ofEpochMilli(plan.oneTimeAt!!).atZone(now.zone)
                require("%02d:%02d".format(time.hour, time.minute) == plan.reminderTimes.single()) { "提醒日期与时间不一致，请重新整理" }
            }
        }
    }
}

object PlanDraftCodec {
    fun encode(plan: PlanVoiceData): String = JSONObject().apply {
        put("name", plan.name); put("category", plan.category); put("repeat", plan.repeatMode)
        put("times", JSONArray(plan.reminderTimes)); put("days", JSONArray(plan.customDays.sorted()))
        put("once", plan.oneTimeAt); put("focus", plan.focusMinutes); put("token", plan.creationToken)
    }.toString()

    fun decode(text: String): PlanVoiceData = JSONObject(text).let { json ->
        val times = json.getJSONArray("times"); val days = json.getJSONArray("days")
        PlanVoiceData(name = json.getString("name"), category = json.getString("category"), repeatMode = json.getString("repeat"),
            reminderTimes = (0 until times.length()).map { times.getString(it) },
            customDays = (0 until days.length()).map { days.getInt(it) }.toSet(),
            oneTimeAt = json.optLong("once").takeIf { it > 0 }, focusMinutes = json.optInt("focus").takeIf { it > 0 },
            creationToken = json.optString("token").takeIf { it.isNotBlank() && it != "null" })
    }
}
