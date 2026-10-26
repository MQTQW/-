package com.example.myplan.assistant.api

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** Model output is untrusted. Validation here is independent from the service validation. */
internal object AssistJson {
    private val categories = setOf("日常", "工作", "学习", "健康", "紧急", "其他")
    private val durations = setOf(15, 25, 45)
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

    fun parse(text: String, now: ZonedDateTime): AssistParseResult = validated {
        val value = JSONObject(text)
        value.requireKeys("draft", "question")
        val question = if (value.isNull("question")) null else value.strictText("question", 160)
        if (value.isNull("draft")) {
            require(question != null)
            return@validated AssistParseResult(null, question)
        }
        val draft = value.getJSONObject("draft")
        draft.requireKeys("title", "category", "repeatMode", "date", "reminderTimes", "customDays", "focusMinutes")
        val title = draft.strictText("title", 80)
        val category = draft.strictText("category", 20).also { require(it in categories) }
        val mode = AssistRepeatMode.valueOf(draft.strictText("repeatMode", 20))
        val date = if (draft.isNull("date")) null else draft.strictText("date", 10).let {
            require(datePattern.matches(it)); LocalDate.parse(it)
        }
        val times = draft.getJSONArray("reminderTimes").mapStrict { value ->
            require(value is String && timePattern.matches(value)); LocalTime.parse(value)
        }.also { require(it.size <= 8 && it.distinct().size == it.size) }
        val days = draft.getJSONArray("customDays").mapStrict { value ->
            require(value is Int && value in 1..7); value
        }.also { require(it.distinct().size == it.size) }
        val focus = if (draft.isNull("focusMinutes")) null else draft.get("focusMinutes").let {
            require(it is Int && it in durations); it
        }
        require(if (mode == AssistRepeatMode.CUSTOM) days.isNotEmpty() else days.isEmpty())
        require(mode == AssistRepeatMode.ONCE || date == null)
        require(question != null || (times.isNotEmpty() && (mode != AssistRepeatMode.ONCE || date != null)))
        if (mode == AssistRepeatMode.ONCE && date != null) times.forEach { time ->
            val local = LocalDateTime.of(date, time)
            require(now.zone.rules.getValidOffsets(local).isNotEmpty())
            require(local.atZone(now.zone).toInstant().isAfter(now.toInstant()))
        }
        AssistParseResult(AssistDraft(title, category, mode, date, times, days, focus), question)
    }

    fun steps(text: String): List<AssistStepSuggestion> = validated {
        val value = JSONObject(text)
        value.requireKeys("steps")
        val steps = value.getJSONArray("steps").mapStrict { raw ->
            require(raw is JSONObject)
            raw.requireKeys("title", "focusMinutes")
            val minutes = raw.get("focusMinutes")
            require(minutes is Int && minutes in durations)
            AssistStepSuggestion(raw.strictText("title", 80), minutes)
        }
        require(steps.size in 1..8 && steps.map { it.title }.distinct().size == steps.size)
        steps
    }

    internal fun validInput(value: String, maximum: Int, allowEmpty: Boolean = false): Boolean =
        value.length <= maximum && (allowEmpty || value.isNotBlank()) &&
            value.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 }

    private fun JSONObject.requireKeys(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet())
    }
    private fun JSONObject.strictText(key: String, maximum: Int): String {
        val value = get(key)
        require(value is String && validInput(value, maximum))
        return value.trim()
    }
    private fun <T> JSONArray.mapStrict(transform: (Any) -> T): List<T> = (0 until length()).map { transform(get(it)) }
    private inline fun <T> validated(block: () -> T): T = try { block() }
        catch (_: Exception) { throw AssistException(AssistError.INVALID_RESPONSE, "AI 返回的内容暂时无法使用，请重试或手动编辑。") }
}
