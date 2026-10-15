package com.example.myplan.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import android.os.Build
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAlarm(
        planId: Long, reminderId: Long, triggerTimeMillis: Long,
        planName: String, planDescription: String?,
        repeatMode: String? = null, customDays: String? = null,
        hour: Int = -1, minute: Int = -1,
    ) {
        check(canScheduleExactAlarms()) { "请在系统设置中允许闹钟和提醒" }
        if (triggerTimeMillis <= System.currentTimeMillis()) {
            Log.w("AlarmScheduler", "跳过已过期闹钟")
            return
        }

        val broadcastIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("plan_id", planId)
            putExtra("reminder_id", reminderId)
            putExtra("plan_name", planName)
            putExtra("plan_desc", planDescription)
            putExtra("repeat_mode", repeatMode)
            putExtra("custom_days", customDays)
            putExtra("hour", hour)
            putExtra("minute", minute)
        }
        val broadcastPi = PendingIntent.getBroadcast(
            context, reminderId.toInt(), broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, com.example.myplan.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPi = PendingIntent.getActivity(
            context, reminderId.toInt(), showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTimeMillis, showPi),
            broadcastPi
        )
        Log.d("AlarmScheduler", "已调度闹钟 planId=$planId remId=$reminderId")
    }

    fun cancelAlarm(reminderId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, reminderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
        pi.cancel()
    }

    fun cancelAllForPlan(reminderIds: List<Long>) = reminderIds.forEach { cancelAlarm(it) }

    fun nextFireTime(plan: PlanEntity, reminder: ReminderEntity, after: Long = System.currentTimeMillis()): Long? {
        if (!plan.isEnabled || !reminder.isEnabled) return null
        val days = plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return when (reminder.legacyRepeatMode) {
            "NONE" -> reminder.legacyTime?.takeIf { it > after }
            "DAILY" -> computeNextFireTime(reminder.hour, reminder.minute, "EVERY_DAY", emptySet(), after)
            "WEEKLY" -> {
                val original = Calendar.getInstance().apply { timeInMillis = reminder.legacyTime ?: return null }
                computeNextFireTime(reminder.hour, reminder.minute, "CUSTOM",
                    setOf(calendarDayToIndex(original.get(Calendar.DAY_OF_WEEK))), after)
            }
            else -> computeNextFireTime(reminder.hour, reminder.minute, plan.repeatMode, days, after)
        }
    }

    fun computeNextFireTime(hour: Int, minute: Int, repeatMode: String, customDays: Set<Int>, after: Long = System.currentTimeMillis()): Long? {
        require(hour in 0..23 && minute in 0..59)
        val cal = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= after) cal.add(Calendar.DAY_OF_YEAR, 1)
        return when (repeatMode) {
            "EVERY_DAY" -> cal.timeInMillis
            "WEEKDAYS" -> {
                while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            "CUSTOM" -> {
                val days = customDays.filter { it in 1..7 }.toSet()
                if (days.isEmpty()) return null
                val targets = days.map { dayIndexToCalendarDay(it) }.toSet()
                repeat(7) { if (cal.get(Calendar.DAY_OF_WEEK) in targets) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
                null
            }
            else -> null
        }
    }

    companion object {

        /** 调度午夜重置闹钟（每天 00:01） */
        fun scheduleMidnightReset(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            val intent = Intent(context, MidnightReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 9999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Housekeeping should not replace the user's next alarm-clock indicator.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }

        fun dayIndexToCalendarDay(index: Int): Int = if (index == 7) Calendar.SUNDAY else index + 1
        fun calendarDayToIndex(calDay: Int): Int = if (calDay == Calendar.SUNDAY) 7 else calDay - 1
        fun formatRepeatInfo(repeatMode: String, customDays: Set<Int>, reminderCount: Int): String {
            val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            val modeText = when (repeatMode) {
                "EVERY_DAY" -> "每天"; "WEEKDAYS" -> "工作日"
                "CUSTOM" -> customDays.sorted().joinToString(",") { dayNames[it - 1] }
                else -> "每天"
            }
            return "📅 $modeText · ${reminderCount}个提醒"
        }
    }

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleTestAlarm() {
        check(canScheduleExactAlarms()) { "请先允许闹钟和提醒权限" }
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, TestReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 60_000, null), pi)
    }
}
