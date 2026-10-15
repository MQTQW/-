package com.example.myplan.focus.data.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*

class AppUsageRepository(
    private val context: Context,
    private val database: FocusDatabase
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val dao = database.usageLogDao()

    fun getToday(): String = dateFormat.format(Date())

    fun getUsageForDate(date: String): Flow<List<DailyUsageEntity>> =
        dao.getUsageForDate(date)

    suspend fun getLockedAppsForToday(): List<DailyUsageEntity> =
        dao.getLockedAppsForDate(getToday())

    suspend fun getUnlockedAppsForToday(): List<String> = dao.getUnlockedAppsForDate(getToday())

    fun querySystemUsage(): Map<String, Long> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val result = mutableMapOf<String, Long>()
        for (stats in usageStatsList) {
            if (stats.totalTimeInForeground > 0) {
                result[stats.packageName] = stats.totalTimeInForeground
            }
        }
        return result
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
        if (existing != null) {
            dao.updateUsage(existing.id, timeMillis)
        } else {
            dao.insert(
                DailyUsageEntity(
                    packageName = packageName,
                    appName = appName,
                    date = today,
                    totalTimeMillis = timeMillis
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
