package com.example.myplan.focus.data.repository

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.myplan.focus.ForegroundUsage
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow
import java.util.*

class AppUsageRepository(
    private val context: Context,
    private val database: FocusDatabase
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager

    private val dao = database.usageLogDao()

    fun getToday(): String = java.time.LocalDate.now().toString()

    fun getUsageForDate(date: String): Flow<List<DailyUsageEntity>> =
        dao.getUsageForDate(date)

    suspend fun getLockedAppsForToday(): List<DailyUsageEntity> =
        dao.getLockedAppsForDate(getToday())

    suspend fun getUnlockedAppsForToday(): List<String> = dao.getUnlockedAppsForDate(getToday())

    fun querySystemUsage(): Map<String, Long> {
        val endTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = endTime
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis

        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val result = mutableMapOf<String, Long>()
        for (stats in usageStatsList) {
            if (stats.totalTimeInForeground > 0) {
                result[stats.packageName] = maxOf(result.getOrDefault(stats.packageName, 0), stats.totalTimeInForeground)
            }
        }

        // A daily snapshot can omit the currently resumed activity until it is paused.
        // Include yesterday to identify a session already in progress at today's midnight.
        val lookbackStart = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val events = mutableListOf<ForegroundUsage.Event>()
        try {
            val usageEvents = usageStatsManager.queryEvents(lookbackStart, endTime)
            val event = UsageEvents.Event()
            while (usageEvents != null && usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundUsage.Kind.RESUME
                    UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundUsage.Kind.PAUSE
                    UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundUsage.Kind.STOP
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundUsage.Kind.SCREEN_OFF
                    UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundUsage.Kind.SHUTDOWN
                    UsageEvents.Event.DEVICE_STARTUP -> ForegroundUsage.Kind.STARTUP
                    else -> null
                }
                if (kind != null) events.add(ForegroundUsage.Event(event.timeStamp, kind, event.packageName, event.className))
            }
        } catch (_: SecurityException) {
            // Usage access can be revoked while the service is running. Keep the snapshot fallback.
            return result
        }
        return ForegroundUsage.merge(result, events, startTime, endTime)
    }

    fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    fun getInstalledApps(): List<Pair<String, String>> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val activities = packageManager.queryIntentActivities(intent, 0)
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.activityInfo.packageName to getAppName(it.activityInfo.packageName) }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    suspend fun updateUsage(packageName: String, appName: String, timeMillis: Long) {
        val today = getToday()
        val existing = dao.getUsageForPackageAndDate(packageName, today)
        val category = database.appLimitDao().getCategoryForPackage(packageName) ?: existing?.categoryId
        if (existing != null) {
            dao.updateUsage(existing.id, timeMillis)
            if (existing.categoryId != category) dao.setCategory(packageName, category)
        } else {
            dao.insert(
                DailyUsageEntity(
                    packageName = packageName,
                    appName = appName,
                    date = today,
                    totalTimeMillis = timeMillis, categoryId = category
                )
            )
        }
    }

    suspend fun lockApp(packageName: String) {
        val today = getToday()
        val existing = dao.getUsageForPackageAndDate(packageName, today)
        if (existing != null) {
            dao.setLocked(existing.id, true)
        }
    }

    suspend fun unlockAllForToday() {
        dao.unlockAllForDate(getToday())
    }

    suspend fun unlockAppForToday(packageName: String) {
        val today = getToday()
        val existing = dao.getUsageForPackageAndDate(packageName, today)
        if (existing != null) {
            dao.unlockPackageForDate(packageName, today)
        }
    }

    suspend fun getUsageForPackageAndDate(packageName: String, date: String): DailyUsageEntity? {
        return dao.getUsageForPackageAndDate(packageName, date)
    }

    suspend fun getTotalForCategoryOnDate(categoryId: Long, date: String): Long? {
        return dao.getTotalForCategoryOnDate(categoryId, date)
    }

    suspend fun getPackagesForCategory(categoryId: Long): List<String> =
        dao.getPackagesForCategory(categoryId, getToday())

    suspend fun cleanup() {
        dao.deleteOldRecords(getToday())
    }
}
