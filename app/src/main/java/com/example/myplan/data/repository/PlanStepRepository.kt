package com.example.myplan.data.repository

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanStepEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class PlanStepRepository(private val db: PlanDatabase) {
    fun observe(planId: Long): Flow<List<PlanStepEntity>> = db.planStepDao().observe(planId)

    suspend fun getSteps(planId: Long): List<PlanStepEntity> = db.planStepDao().getSteps(planId)

    /** Step progress is separate from the parent's completion and credit ledger. */
    suspend fun setCompleted(planId: Long, stepId: Long, completed: Boolean, date: String): Boolean = db.withTransaction {
        LocalDate.parse(date)
        val plan = db.planDao().getPlanById(planId) ?: return@withTransaction false
        if (plan.isCompleted) return@withTransaction false
        if (db.planStepDao().getStep(planId, stepId) == null) return@withTransaction false
        db.planStepDao().setCompleted(planId, stepId, completed, if (completed) date else null)
        true
    }
}
