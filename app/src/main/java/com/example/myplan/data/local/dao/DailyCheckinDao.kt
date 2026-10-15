package com.example.myplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myplan.data.local.entity.DailyCheckin
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyCheckinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkin: DailyCheckin)

    @Query("SELECT * FROM daily_checkin ORDER BY date DESC LIMIT 365")
    suspend fun getAll(): List<DailyCheckin>

    @Query("SELECT * FROM daily_checkin WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DailyCheckin?

    @Query("SELECT * FROM daily_checkin ORDER BY date DESC LIMIT 365")
    fun observeAll(): Flow<List<DailyCheckin>>

    @Query("SELECT * FROM daily_checkin ORDER BY date DESC")
    suspend fun getAllForBackup(): List<DailyCheckin>
}
