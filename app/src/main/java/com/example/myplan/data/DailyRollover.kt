package com.example.myplan.data

import android.content.Context
import android.content.Intent
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.repository.TaskRepository
import com.example.myplan.focus.util.FocusConstants
import com.example.myplan.notification.AlarmScheduler
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DailyRollover {
    private val mutex = Mutex()
    private var scheduledForDate: String? = null

    suspend fun ensureToday(context: Context, forceSchedule: Boolean = false): Boolean = mutex.withLock {
        val today = LocalDate.now().toString()
        val prefs = SettingsManager.getPrefs(context)
        val changed = prefs.getString("last_daily_reset", null) != today
        if (changed) {
            PlanRepository(PlanDatabase.getInstance(context)).resetDailyCompletions(today)
            TaskRepository(FocusDatabase.getInstance(context)).resetDailyTasks()
            check(prefs.edit().putString("last_daily_reset", today).commit())
            context.sendBroadcast(Intent(FocusConstants.ACTION_DAILY_RESET).setPackage(context.packageName))
        }
        (context.applicationContext as? MyPlanApp)?.creditRepository?.refreshDate()
        if (forceSchedule || scheduledForDate != today) {
            AlarmScheduler.scheduleMidnightReset(context)
            scheduledForDate = today
        }
        changed
    }
}
