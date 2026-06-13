package com.example.myplan.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {

    @Transaction
    @Query("SELECT * FROM plans ORDER BY created_at DESC")
    fun observeAllPlans(): Flow<List<PlanWithReminders>>

    @Transaction
    @Query("SELECT * FROM plans WHERE id = :id")
    fun observePlan(id: Long): Flow<PlanWithReminders?>

    @Transaction
    @Query("SELECT * FROM plans WHERE is_completed = 0 ORDER BY created_at DESC")
    fun observeActivePlans(): Flow<List<PlanWithReminders>>

    @Transaction
    @Query("SELECT * FROM plans WHERE is_completed = 1 ORDER BY created_at DESC")
    fun observeCompletedPlans(): Flow<List<PlanWithReminders>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: PlanEntity): Long

    @Update
    suspend fun updatePlan(plan: PlanEntity)

    @Delete
    suspend fun deletePlan(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun deletePlanById(id: Long)

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getPlanById(id: Long): PlanEntity?
}
