package com.example.myplan.focus.data.dao

import androidx.room.*
import com.example.myplan.focus.data.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun getAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY createdAt DESC")
    fun getUncompleted(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0")
    fun getUncompletedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE tasks SET isCompleted = 1, completedAt = :timestamp, lastCompletedDate = :date WHERE id = :id")
    suspend fun markCompleted(id: Long, timestamp: Long = System.currentTimeMillis(), date: String)

    @Query("UPDATE tasks SET isCompleted = 0, completedAt = NULL WHERE id = :id")
    suspend fun markUncompleted(id: Long)

    @Query("UPDATE tasks SET isCompleted = 0, completedAt = NULL, lastCompletedDate = NULL WHERE isRecurring = 1 AND isCompleted = 1")
    suspend fun resetRecurringTasks()
}
