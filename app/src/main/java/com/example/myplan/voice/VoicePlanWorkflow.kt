package com.example.myplan.voice

sealed class WorkflowStep {
    data class Ask(val field: String, val prompt: String) : WorkflowStep()
    data class Confirm(val data: PlanVoiceData, val summary: String) : WorkflowStep()
    data class Success(val planName: String) : WorkflowStep()
    object Cancelled : WorkflowStep()
}

enum class StepField {
    PLAN_NAME, DESCRIPTION, REPEAT_MODE, CUSTOM_DAYS, REMINDER_TIME, CONFIRM
}

class VoicePlanWorkflow {

    private val data = PlanVoiceData()
    private val reminders = mutableListOf<String>()
    var currentStep: StepField = StepField.PLAN_NAME
        private set
    var isDone: Boolean = false
        private set

    fun getPrompt(): String = when (currentStep) {
        StepField.PLAN_NAME -> "你的计划叫什么名字？"
        StepField.DESCRIPTION -> "简单描述一下？或者说跳过。"
        StepField.REPEAT_MODE -> "每天提醒、工作日、还是自定义？"
        StepField.CUSTOM_DAYS -> "选哪几天？比如周一三五。"
        StepField.REMINDER_TIME -> if (reminders.isEmpty()) "第一个提醒时间？"
            else "还要加别的提醒时间吗？不用就说不用了。"
        StepField.CONFIRM -> buildSummary()
    }

    fun handleAnswer(text: String): WorkflowStep {
        val t = text.trim()
        when (currentStep) {
            StepField.PLAN_NAME -> {
                if (t.isNotBlank() && t.length > 1 && !t.contains("不用") && !t.contains("跳过")) {
                    data.name = t.take(30)
                    currentStep = StepField.DESCRIPTION
                    return WorkflowStep.Ask("描述", getPrompt())
                }
                return WorkflowStep.Ask("名称", "请说出计划的名称，比如学英语、吃药。")
            }

            StepField.DESCRIPTION -> {
                currentStep = StepField.REPEAT_MODE
                return WorkflowStep.Ask("重复模式", getPrompt())
            }

            StepField.REPEAT_MODE -> {
                when {
                    t.contains("每天") || t.contains("天天") -> data.repeatMode = "EVERY_DAY"
                    t.contains("工作") -> data.repeatMode = "WEEKDAYS"
                    t.contains("自定义") || t.contains("自己选") -> {
                        data.repeatMode = "CUSTOM"
                        currentStep = StepField.CUSTOM_DAYS
                        return WorkflowStep.Ask("自定义星期", getPrompt())
                    }
                    else -> return WorkflowStep.Ask("重复模式", "每天、工作日、还是自定义？")
                }
                currentStep = StepField.REMINDER_TIME
                return WorkflowStep.Ask("提醒时间", getPrompt())
            }

            StepField.CUSTOM_DAYS -> {
                val days = parseDays(t)
                if (days.isNotEmpty()) {
                    data.customDays = days
                    currentStep = StepField.REMINDER_TIME
                    return WorkflowStep.Ask("提醒时间", getPrompt())
                }
                return WorkflowStep.Ask("自定义星期", "请说出你想要的星期，比如周一三五。")
            }

            StepField.REMINDER_TIME -> {
                if (t.contains("不用") || t.contains("好了") || t.contains("可以") || t.contains("够了") || t.contains("不用了")) {
                    if (reminders.isNotEmpty()) {
                        data.reminderTimes = reminders.toList()
                        currentStep = StepField.CONFIRM
                        return WorkflowStep.Confirm(data, buildSummary())
                    }
                    return WorkflowStep.Ask("提醒时间", "至少需要一个提醒时间，几点呢？")
                }
                val time = parseTime(t)
                if (time != null) {
                    reminders.add(time)
                    currentStep = StepField.REMINDER_TIME
                    return WorkflowStep.Ask("提醒时间", getPrompt())
                }
                val times = SlotFiller.parse(t).reminderTimes
                if (times != null && times.isNotEmpty()) {
                    reminders.addAll(times)
                    currentStep = StepField.REMINDER_TIME
                    return WorkflowStep.Ask("提醒时间", getPrompt())
                }
                return WorkflowStep.Ask("提醒时间", "我没听清时间，能再说一遍吗？比如早上8点。")
            }

            StepField.CONFIRM -> {
                if (t.contains("确认") || t.contains("好的") || t.contains("行") || t.contains("可以") || t.contains("创建") || t.contains("对")) {
                    isDone = true
                    return WorkflowStep.Success(data.name)
                }
                if (t.contains("不对") || t.contains("改") || t.contains("重新")) {
                    currentStep = StepField.PLAN_NAME
                    data.name = ""
                    reminders.clear()
                    return WorkflowStep.Ask("名称", getPrompt())
                }
                return WorkflowStep.Ask("确认", "请说确认或修改。")
            }
        }
    }

    fun cancel(): WorkflowStep {
        isDone = true
        return WorkflowStep.Cancelled
    }

    private fun buildSummary(): String {
        val sb = StringBuilder("好的——「${data.name}」")
        val repeat = when (data.repeatMode) {
            "WEEKDAYS" -> "工作日"
            "CUSTOM" -> {
                val names = data.customDays.map {
                    when (it) { 1->"一" 2->"二" 3->"三" 4->"四" 5->"五" 6->"六" 7->"日" else->"" }
                }.joinToString("")
                "每周$names"
            }
            else -> "每天"
        }
        sb.append("，$repeat")
        sb.append("${reminders.joinToString("、")}提醒你。确认创建吗？")
        return sb.toString()
    }

    private fun parseDays(text: String): Set<Int> {
        val days = mutableSetOf<Int>()
        if (text.contains("一") || text.contains("1")) days.add(1)
        if (text.contains("二") || text.contains("2")) days.add(2)
        if (text.contains("三") || text.contains("3")) days.add(3)
        if (text.contains("四") || text.contains("4")) days.add(4)
        if (text.contains("五") || text.contains("5")) days.add(5)
        if (text.contains("六") || text.contains("6")) days.add(6)
        if (text.contains("日") || text.contains("7")) days.add(7)
        return days
    }

    private fun parseTime(text: String): String? {
        // Arabic: "8点", "8:00"
        val r1 = Regex("""(\d{1,2})\s*[点:：]""")
        val m1 = r1.find(text)
        if (m1 != null) {
            val h = m1.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: return null
            return "${adjustAmPm(h, text).toString().padStart(2, '0')}:00"
        }
        // Chinese: "八点"
        val cnNums = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
            "十" to 10, "十一" to 11, "十二" to 12,
            "十三" to 13, "十四" to 14, "十五" to 15,
            "十六" to 16, "十七" to 17, "十八" to 18, "十九" to 19,
            "二十" to 20, "二十一" to 21, "二十二" to 22, "二十三" to 23
        )
        for ((cn, num) in cnNums) {
            val idx = text.indexOf(cn)
            if (idx >= 0) {
                val after = text.substring(idx + cn.length)
                if (after.startsWith("点") || after.startsWith("时")) {
                    return "${adjustAmPm(num, text).toString().padStart(2, '0')}:00"
                }
            }
        }
        return null
    }

    private fun adjustAmPm(h: Int, text: String): Int {
        return when {
            text.contains("晚") || text.contains("下") -> if (h == 12) 12 else h + 12
            text.contains("早") || text.contains("上") -> if (h == 12) 0 else h
            text.contains("中午") -> if (h < 12) h + 12 else h
            else -> if (h < 6) h + 12 else h
        }.coerceIn(0, 23)
    }

    fun getPlanData(): PlanVoiceData = data.copy(reminderTimes = reminders.toList())
}
