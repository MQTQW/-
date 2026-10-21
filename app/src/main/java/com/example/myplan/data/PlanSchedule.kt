package com.example.myplan.data

import com.example.myplan.data.local.relation.PlanWithReminders
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Shared by today's list and the home-screen widget. Overdue one-time tasks remain pending. */
fun isScheduledOn(plan: PlanWithReminders, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (!plan.plan.isEnabled) return false
    if (plan.plan.repeatMode == "ONCE") {
        val date = plan.plan.oneTimeAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return false
        return plan.reminders.any { it.isEnabled } && (date == day || (date < day && !plan.plan.isCompleted))
    }
    val weekday = day.dayOfWeek.value
    val days = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    return plan.reminders.any { reminder ->
        if (!reminder.isEnabled) false else when (reminder.legacyRepeatMode) {
            "NONE" -> reminder.legacyTime?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == day } ?: false
            "DAILY" -> true
            "WEEKLY" -> reminder.legacyTime?.let { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek.value == weekday } ?: false
            else -> when (plan.plan.repeatMode) { "EVERY_DAY" -> true; "WEEKDAYS" -> weekday in 1..5; "CUSTOM" -> weekday in days; else -> false }
        }
    }
}
