package com.example.myplan.data.repository

import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import kotlinx.coroutines.flow.Flow

class PlanRepository(private val db: PlanDatabase) {

    private val planDao = db.planDao()
    private val reminderDao = db.reminderDao()

    // ── 计划 ──

    fun observeAllPlans(): Flow<List<PlanWithReminders>> = planDao.observeAllPlans()

    fun observeActivePlans(): Flow<List<PlanWithReminders>> = planDao.observeActivePlans()

    fun observeCompletedPlans(): Flow<List<PlanWithReminders>> = planDao.observeCompletedPlans()

    fun observePlan(id: Long): Flow<PlanWithReminders?> = planDao.observePlan(id)

    suspend fun insertPlan(plan: PlanEntity): Long = planDao.insertPlan(plan)

    suspend fun updatePlan(plan: PlanEntity) = planDao.updatePlan(plan)

    suspend fun deletePlan(plan: PlanEntity) {
        // CASCADE 自动删除关联提醒
        planDao.deletePlan(plan)
    }

    suspend fun getPlanById(id: Long): PlanEntity? = planDao.getPlanById(id)

    // ── 提醒 ──

    suspend fun getRemindersByPlanId(planId: Long): List<ReminderEntity> =
        reminderDao.getRemindersByPlanId(planId)

    suspend fun getAllEnabledReminders(): List<ReminderEntity> =
        reminderDao.getAllEnabledReminders()

    suspend fun getReminderById(id: Long): ReminderEntity? =
        reminderDao.getReminderById(id)

    suspend fun insertReminder(reminder: ReminderEntity): Long =
        reminderDao.insertReminder(reminder)

    suspend fun updateReminder(reminder: ReminderEntity) =
        reminderDao.updateReminder(reminder)

    suspend fun deleteReminder(reminder: ReminderEntity) =
        reminderDao.deleteReminder(reminder)

    suspend fun deleteRemindersByPlanId(planId: Long) =
        reminderDao.deleteRemindersByPlanId(planId)

    // ── 同步：保存计划及其提醒 ──

    suspend fun savePlanWithReminders(
        plan: PlanEntity,
        reminders: List<ReminderEntity>
    ) {
        val planId = if (plan.id == 0L) {
            planDao.insertPlan(plan)
        } else {
            planDao.updatePlan(plan)
            plan.id
        }

        // 删除旧提醒，重新插入
        reminderDao.deleteRemindersByPlanId(planId)
        reminders.forEach { reminder ->
            reminderDao.insertReminder(reminder.copy(planId = planId))
        }
    }
}
