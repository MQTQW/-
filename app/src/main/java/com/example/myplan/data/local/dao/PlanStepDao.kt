package com.example.myplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myplan.data.local.entity.PlanStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanStepDao {
    @Query("SELECT * FROM plan_steps ORDER BY plan_id, position, id")
    fun observeAll(): Flow<List<PlanStepEntity>>

    @Query("SELECT * FROM plan_steps WHERE plan_id = :planId ORDER BY position, id")
    fun observe(planId: Long): Flow<List<PlanStepEntity>>

    @Query("SELECT * FROM plan_steps WHERE plan_id = :planId ORDER BY position, id")
    suspend fun getSteps(planId: Long): List<PlanStepEntity>

    @Query("SELECT * FROM plan_steps WHERE plan_id = :planId AND id = :stepId")
    suspend fun getStep(planId: Long, stepId: Long): PlanStepEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(step: PlanStepEntity): Long

    @Query("UPDATE plan_steps SET is_completed = :completed, completed_date = :date WHERE plan_id = :planId AND id = :stepId")
    suspend fun setCompleted(planId: Long, stepId: Long, completed: Boolean, date: String?)

    @Query("DELETE FROM plan_steps WHERE plan_id = :planId")
    suspend fun deleteForPlan(planId: Long)
}
