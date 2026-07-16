package com.example.myplan.voice

/**
 * NLU 槽位提取器 — V1 纯规则引擎。
 * 从用户自然语言中解析：计划名称、提醒时间、重复模式、分类。
 * V1.5 引入 DeepSeek Function Calling 在线增强（VoiceApiService）。
 */
data class SlotResult(
    val planName: String? = null,
    val reminderTimes: List<String>? = null,
    val repeatMode: String? = null,
    val customDays: Set<Int>? = null,
    val category: String? = null,
    val isCancel: Boolean = false,
    val isConfirm: Boolean = false,
    val confidence: Float = 1.0f
)

object SlotFiller {

    // ---- Cancel / Confirm detection ----
    private val cancelWords = setOf("取消", "算了", "不用了", "退出", "不做了", "没事", "不用")
    private val confirmWords = setOf("确认", "好的", "行", "可以", "是的", "对", "嗯", "创建", "没问题")

    // ---- Time regex patterns ----
    private val timePattern = Regex(
        """(早上|上午|中午|下午|晚上|凌晨|早|晚|中)?\s*(\d{1,2})\s*[点:：]\s*(\d{1,2})?\s*(分)?"""
    )
    private val timeMultiPattern = Regex("""(\d{1,2})[点:：]\s*(\d{1,2})?""")

    // ---- Repeat mode keywords ----
    private val dailyWords = setOf("每天", "天天", "每一天", "每日", "每一天都")
    private val weekdayWords = setOf("工作日", "周一到周五", "周一至周五", "上班日")
    private val weekendWords = setOf("周末", "周六日", "星期六日")

    // ---- Category keywords ----
    private val categoryMap = mapOf(
        "学习" to setOf("学习", "英语", "看书", "读书", "课程", "考试", "复习", "背", "作业"),
        "工作" to setOf("工作", "开会", "项目", "汇报", "任务", "报告"),
        "健康" to setOf("健身", "运动", "跑步", "吃药", "喝水", "维生素", "锻炼", "游泳", "瑜伽"),
        "紧急" to setOf("紧急", "重要", "马上", "赶紧")
    )

    // ---- Trigger/noise words to strip from plan name ----
    private val noiseWords = setOf(
        "提醒我", "帮我", "帮我定", "给我", "帮我设置", "帮我制定",
        "制定", "创建", "加一个", "加个", "添加", "新建",
        "的计划", "的提醒", "的闹钟", "一下", "一个"
    )

    /**
     * Parse user text. Returns SlotResult with extracted fields.
     */
    fun parse(text: String): SlotResult {
        val trimmed = text.trim().replace("？", "").replace("?", "")

        // Cancel / Confirm check (highest priority)
        if (cancelWords.any { trimmed.contains(it) && trimmed.length <= 4 }) {
            return SlotResult(isCancel = true)
        }
        if (confirmWords.any { trimmed == it }) {
            return SlotResult(isConfirm = true)
        }

        // Extract time(s)
        val times = extractTimes(trimmed)

        // Extract repeat mode
        val (repeatMode, customDays) = extractRepeat(trimmed)

        // Extract category
        val category = extractCategory(trimmed)

        // Extract plan name (remaining text after removing extracted tokens)
        val name = extractPlanName(trimmed, times, repeatMode, category)

        val confidence = if (times.isNotEmpty() || name.isNotBlank()) 0.85f else 0.3f

        return SlotResult(
            planName = name.takeIf { it.isNotBlank() },
            reminderTimes = times.takeIf { it.isNotEmpty() },
            repeatMode = repeatMode,
            customDays = customDays,
            category = category,
            confidence = confidence
        )
    }

    // ---- Private helpers ----

    private fun extractTimes(text: String): List<String> {
        val results = mutableListOf<String>()

        // Find all time expressions
        val matches = timePattern.findAll(text)
        for (m in matches) {
            val period = m.groupValues[1]
            val hour = m.groupValues[2].toIntOrNull() ?: continue
            var h = hour

            // Apply period offset
            when {
                period in listOf("凌晨") -> { if (h == 12) h = 0 }
                period in listOf("下午", "晚上") && h != 12 -> h += 12
                period in listOf("中午") && h < 12 -> h += 12
            }

            // Handle "and" / "、" / "还有" separators
            val hh = h.toString().padStart(2, '0')
            val mm = (m.groupValues[3].toIntOrNull() ?: 0).toString().padStart(2, '0')
            results.add("$hh:$mm")
        }

        return results.distinct().take(5) // Max 5 reminders
    }

    private fun extractRepeat(text: String): Pair<String, Set<Int>?> {
        // Check weekday
        if (weekdayWords.any { text.contains(it) }) {
            return "WEEKDAYS" to null
        }
        // Check weekend
        if (weekendWords.any { text.contains(it) }) {
            return "CUSTOM" to setOf(6, 7)
        }
        // Check daily
        if (dailyWords.any { text.contains(it) }) {
            return "EVERY_DAY" to null
        }
        // Check custom day patterns: "周一三五", "135", "星期一三五"
        val customPattern = Regex("""[周星期]\s*([一二三四五六日1-7]+)""")
        val customMatch = customPattern.find(text)
        if (customMatch != null) {
            val days = customMatch.groupValues[1].mapNotNull { dayCharToInt(it) }.toSet()
            if (days.isNotEmpty()) return "CUSTOM" to days
        }
        // Default: every day
        return "EVERY_DAY" to null
    }

    private fun dayCharToInt(c: Char): Int? = when (c) {
        '一', '1' -> 1; '二', '2' -> 2; '三', '3' -> 3
        '四', '4' -> 4; '五', '5' -> 5; '六', '6' -> 6; '日', '7' -> 7
        else -> null
    }

    private fun extractCategory(text: String): String? {
        for ((cat, keywords) in categoryMap) {
            if (keywords.any { text.contains(it) }) return cat
        }
        return null // null = use default "日常"
    }

    private fun extractPlanName(
        text: String,
        times: List<String>,
        repeatMode: String,
        category: String?
    ): String {
        var result = text

        // Remove noise words
        for (word in noiseWords) {
            result = result.replace(word, "")
        }

        // Remove time expressions (use regex global)
        result = timePattern.replace(result, "")

        // Remove repeat keywords
        for (word in dailyWords + weekdayWords + weekendWords) {
            result = result.replace(word, "")
        }

        // Remove category keywords
        if (category != null) {
            for (word in categoryMap[category] ?: emptySet()) {
                result = result.replace(word, "")
            }
        }

        // Clean up
        result = result.replace(Regex("""[,，。.、；;：:！!？?\s]+"""), " ").trim()
        result = result.replace(Regex("""[的提醒计划闹钟]$"""), "").trim()

        return result.take(30) // Max 30 chars
    }
}
