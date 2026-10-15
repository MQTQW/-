package com.example.myplan.data.local.dao

import androidx.room.*
import com.example.myplan.data.local.entity.PlanCompletion

@Dao
interface CompletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(completion: PlanCompletion)

    @Query("SELECT * FROM plan_completions WHERE planId = :id AND date = :date")
    suspend fun get(id: Long, date: String): PlanCompletion?

    @Query("SELECT * FROM plan_completions ORDER BY date DESC, planId")
    suspend fun getAll(): List<PlanCompletion>

    @Query("UPDATE plans SET is_completed = EXISTS (SELECT 1 FROM plan_completions c WHERE c.planId = plans.id AND c.date = :date AND c.completed = 1)")
    suspend fun restoreForDate(date: String)
}
