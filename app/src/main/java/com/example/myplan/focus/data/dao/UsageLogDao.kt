package com.example.myplan.focus.data.dao

import androidx.room.*
import com.example.myplan.focus.data.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageLogDao {

    @Query("SELECT * FROM daily_usage WHERE date = :date ORDER BY totalTimeMillis DESC")
    fun getUsageForDate(date: String): Flow<List<DailyUsageEntity>>

    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    suspend fun getUsageForPackageAndDate(packageName: String, date: String): DailyUsageEntity?

    @Query("SELECT * FROM daily_usage WHERE date = :date AND isLocked = 1")
    suspend fun getLockedAppsForDate(date: String): List<DailyUsageEntity>

    @Query("SELECT SUM(totalTimeMillis) FROM daily_usage WHERE categoryId = :categoryId AND date = :date")
    suspend fun getTotalForCategoryOnDate(categoryId: Long, date: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: DailyUsageEntity): Long

    @Update
    suspend fun update(usage: DailyUsageEntity)

    @Query("UPDATE daily_usage SET totalTimeMillis = :millis, lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateUsage(id: Long, millis: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE daily_usage SET isLocked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("UPDATE daily_usage SET isLocked = 0 WHERE date = :date")
    suspend fun unlockAllForDate(date: String)

    @Query("DELETE FROM daily_usage WHERE date != :today")
    suspend fun deleteOldRecords(today: String)

    @Query("DELETE FROM daily_usage")
    suspend fun deleteAll()
}
