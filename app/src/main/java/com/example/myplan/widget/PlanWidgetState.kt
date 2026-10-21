package com.example.myplan.widget

import com.example.myplan.data.isScheduledOn
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.notification.AlarmScheduler
import java.time.Instant
import java.time.ZoneId

data class WidgetReminder(val row: PlanWithReminders, val at: Long)
data class PlanWidgetState(val todayCount: Int, val completedCount: Int, val next: WidgetReminder?, val pendingCount: Int) {
    companion object {
        fun from(plans: List<PlanWithReminders>, scheduler: AlarmScheduler, now: Long = System.currentTimeMillis()): PlanWidgetState {
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val rows = plans.filter { isScheduledOn(it, today, zone) }
            val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val next = plans.flatMap { row ->
                // A repeat task completed today is represented by its next eligible day.
                val after = if (row.plan.isCompleted && row.plan.repeatMode != "ONCE") maxOf(now, tomorrow - 1) else now
                row.reminders.mapNotNull { scheduler.nextFireTime(row.plan, it, after)?.let { at -> WidgetReminder(row, at) } }
            }.minWithOrNull(compareBy<WidgetReminder> { it.at }.thenBy { it.row.plan.id })
            return PlanWidgetState(rows.size, rows.count { it.plan.isCompleted }, next, rows.count { !it.plan.isCompleted })
        }
    }
}
