package com.example.myplan.focus.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE id = 1")
    fun observeSession(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE id = 1")
    suspend fun current(): FocusSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(session: FocusSessionEntity)

    @Query("SELECT * FROM timed_unlocks ORDER BY startedAt DESC")
    fun observeUnlocks(): Flow<List<TimedUnlockEntity>>

    @Query("SELECT * FROM timed_unlocks")
    suspend fun allUnlocks(): List<TimedUnlockEntity>

    @Query("SELECT * FROM timed_unlocks WHERE requestId = :requestId")
    suspend fun unlock(requestId: String): TimedUnlockEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUnlock(unlock: TimedUnlockEntity)
}
