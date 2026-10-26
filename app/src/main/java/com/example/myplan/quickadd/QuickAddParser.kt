package com.example.myplan.quickadd

import java.time.*
import java.time.temporal.TemporalAdjusters

data class QuickDraft(
    val title: String,
    val repeatMode: String = "ONCE",
    val customDays: String = "",
    val at: Long? = null,
    val hour: Int = 9,
    val minute: Int = 0,
    val date: String? = null,
    val issue: String? = null,
    val alternatives: List<Long> = emptyList(),
    val focusMinutes: Int? = null,
    val category: String? = null,
    val reminderTimes: List<String> = emptyList(),
)

/** Deliberately bounded, offline grammar. Uncertain times always need a user choice. */
object QuickAddParser {
    private const val N = "[0-9零〇一二两三四五六七八九十]+"
    private val weekdays = "一二三四五六日天"

    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): QuickDraft {
        val text = input.trim().replace('：', ':')
        if (text.isBlank()) return QuickDraft("", issue = "请写下要提醒的事")
        if (text.length > 500) return QuickDraft(text.take(200), issue = "内容太长，请缩短后再保存")
        val removed = BooleanArray(text.length)
        fun consume(match: MatchResult) { match.range.forEach { removed[it] = true } }
        fun title(): String = text.filterIndexed { i, _ -> !removed[i] }
            .replace(Regex("^(请)?(提醒我|帮我记下|记得|提醒)[，,、：:\\s]*"), "")
            .replace(Regex("^(在|的)\\s*"), "").trim(' ', '，', ',', '。', '的').take(200)

        var mode = "ONCE"
        var days = ""
        val recurring = Regex("每天|每个工作日|工作日|每?周一至周五|每周[一二三四五六日天](?:[、,，和及][一二三四五六日天])*").find(text)
        recurring?.let {
            consume(it)
            when {
                it.value == "每天" -> mode = "EVERY_DAY"
                it.value.startsWith("每周") && !it.value.contains("至") -> {
                    mode = "CUSTOM"
                    days = it.value.removePrefix("每周").filter { c -> c in weekdays }
                        .map { c -> (weekdays.indexOf(c) + 1).coerceAtMost(7) }.distinct().sorted().joinToString(",")
                }
                else -> mode = "WEEKDAYS"
            }
        }
        var date: LocalDate? = null
        var problem: String? = null
        val dateMatch = Regex("\\d{4}-\\d{1,2}-\\d{1,2}|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]?|今天|明天|后天|今晚|明早|明晚|(?:下周|本周|周|星期)[一二三四五六日天]")
            .findAll(text).firstOrNull { m -> m.range.none { removed[it] } }
        dateMatch?.let { m ->
            consume(m)
            date = try {
                when (m.value) {
                    "今天", "今晚" -> now.toLocalDate()
                    "明天", "明早", "明晚" -> now.toLocalDate().plusDays(1)
                    "后天" -> now.toLocalDate().plusDays(2)
                    else -> if (m.value.contains('-') || m.value.contains('月')) {
                        val nums = Regex("\\d+").findAll(m.value).map { it.value.toInt() }.toList()
                        if (nums.size == 3) LocalDate.of(nums[0], nums[1], nums[2])
                        else LocalDate.of(now.year, nums[0], nums[1])
                    } else {
                        val weekday = (weekdays.indexOf(m.value.last()) + 1).coerceAtMost(7)
                        if (m.value.startsWith("下周")) now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).plusDays(weekday - 1L)
                        else if (m.value.startsWith("本周")) now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(weekday - 1L)
                        else now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
                    }
                }
            } catch (_: DateTimeException) { problem = "日期无效，请重新选择"; null }
        }
        if (mode != "ONCE" && dateMatch != null) problem = "同时包含日期和重复规则，请确认日期与重复方式"
        if (Regex("每月|每年|每隔|隔天|每两|每[二三四五六七八九十0-9]|月底|下个月|下月|大后天|周末|下星期|下班后|饭后").containsMatchIn(text)) {
            problem = "这类时间表达暂不支持，请手动选择日期和重复方式"
        }
        if (recurring == null && text.contains("每周")) problem = "重复星期不明确，请手动选择"
        if (Regex("[一二两三四五六七八九十]+月|\\d+:\\d+:\\d+").containsMatchIn(text)) problem = "时间表达暂不支持，请手动确认日期和时间"
        val relative = Regex("($N)(分钟|小时)后|半小时后").find(text)
        if (relative != null) {
            consume(relative)
            val minutes = if (relative.value == "半小时后") 30 else number(relative.groupValues[1])?.times(if (relative.groupValues[2] == "小时") 60 else 1)
            val name = title()
            if (minutes == null || minutes !in 1..525600 || dateMatch != null || recurring != null || timeMatches(text).isNotEmpty()) {
                return QuickDraft(name, issue = "相对时间不明确，请手动确认")
            }
            val time = now.plusMinutes(minutes.toLong())
            return QuickDraft(name, at = time.toInstant().toEpochMilli(), hour = time.hour, minute = time.minute,
                date = time.toLocalDate().toString(), issue = if (name.isBlank()) "请补充要提醒的事" else problem)
        }
        val matches = timeMatches(text)
        val match = matches.firstOrNull()
        var hour = 9
        var minute = 0
        var ambiguous = false
        if (matches.size > 1) problem = "检测到多个时间，请一次记录一件事"
        if (match != null) {
            consume(match)
            val raw = match.value
            val period = Regex("凌晨|早上|早晨|上午|中午|下午|晚上|傍晚").find(raw)?.value
                ?: when (dateMatch?.value) { "今晚", "明晚" -> "晚上"; "明早" -> "早上"; else -> null }
            val numeric = raw.replace(Regex("凌晨|早上|早晨|上午|中午|下午|晚上|傍晚"), "").trim()
            val parts = numeric.split(Regex("[:点时]"), limit = 2)
            hour = number(parts[0]) ?: -1
            minute = when {
                parts.getOrNull(1)?.startsWith("半") == true -> 30
                parts.getOrNull(1)?.startsWith("一刻") == true -> 15
                parts.getOrNull(1)?.startsWith("三刻") == true -> 45
                else -> number(parts.getOrNull(1)?.removeSuffix("分").orEmpty().ifEmpty { "0" }) ?: -1
            }
            if (hour !in 0..23 || minute !in 0..59 ||
                (period in listOf("凌晨", "早上", "早晨", "上午", "中午") && hour !in 0..12)) problem = "时间无效，请重新选择"
            else when (period) {
                "下午" -> if (hour in 1..11) hour += 12
                "晚上", "傍晚" -> if (hour in 1..11) hour += 12 else if (hour in 0..12) problem = "请确认是否指次日凌晨，并选择日期和时间"
                "中午" -> if (hour !in 11..12) problem = "中午的时间不明确，请确认"
                "凌晨" -> if (hour == 12) hour = 0
                "早上", "早晨", "上午" -> if (hour == 12) problem = "上午十二点不明确，请确认"
                null -> ambiguous = ':' !in numeric && hour in 1..12
            }
        }
        val name = title()
        if (name.isBlank()) problem = "请补充要提醒的事"
        val base = QuickDraft(name, mode, days, hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59), date = date?.toString())
        if (problem != null) return base.copy(issue = problem)
        if (match == null) return base.copy(issue = "还没有提醒时间，请选一个时间")
        val chosenDate = date ?: now.toLocalDate()
        if (ambiguous) {
            val hours = if (hour == 12) listOf(0, 12) else listOf(hour, hour + 12)
            return base.copy(issue = "请确认是上午还是下午", alternatives = if (mode == "ONCE") hours.mapNotNull { h ->
                resolve(chosenDate, LocalTime.of(h, minute), now.zone)?.toInstant()?.toEpochMilli()?.takeIf { it > now.toInstant().toEpochMilli() }
            } else emptyList())
        }
        if (mode != "ONCE") return base
        val time = resolve(chosenDate, LocalTime.of(hour, minute), now.zone)
            ?: return base.copy(issue = "这个时间受夏令时影响，请重新选择")
        val at = time.toInstant().toEpochMilli()
        if (at <= now.toInstant().toEpochMilli()) return base.copy(date = chosenDate.toString(), issue = "这个时间已经过去，请重新选择")
        return base.copy(at = at, date = chosenDate.toString())
    }

    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val local = date.atTime(time)
        if (zone.rules.getValidOffsets(local).size != 1) return null
        return local.atZone(zone)
    }

    private fun timeMatches(text: String) = Regex("(?:凌晨|早上|早晨|上午|中午|下午|晚上|傍晚)?\\s*$N(?::[0-9]{1,2}|[点时](?:半|一刻|三刻|${N}分?)?)(?![0-9零〇一二两三四五六七八九十])").findAll(text).toList()

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        if (value.isEmpty()) return null
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if ('十' in value) {
            val parts = value.split('十')
            if (parts.size != 2) return null
            val tens = if (parts[0].isEmpty()) 1 else parts[0].singleOrNull()?.let { digits[it] } ?: return null
            val ones = if (parts[1].isEmpty()) 0 else parts[1].singleOrNull()?.let { digits[it] } ?: return null
            return tens * 10 + ones
        }
        return value.singleOrNull()?.let { digits[it] }
    }
}
