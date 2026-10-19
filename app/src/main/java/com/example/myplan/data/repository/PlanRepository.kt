package com.example.myplan.data.repository

import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.example.myplan.data.local.entity.PlanCompletion
import com.example.myplan.data.local.entity.CreditEntry
import com.example.myplan.data.local.entity.DailyCheckin
import com.example.myplan.data.DateProvider

class PlanRepository(private val db: PlanDatabase, private val dates: DateProvider = DateProvider()) {

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
    suspend fun getAllPlans(): List<PlanEntity> = planDao.getAllPlansSync()

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

    // ── 午夜重置：将循环计划的 isCompleted 重置为 false ──
    suspend fun resetDailyCompletions(date: String = dates.currentDate().toString()) =
        db.completionDao().restoreForDate(date)

    suspend fun setCompleted(id: Long, completed: Boolean, date: String = dates.currentDate().toString()) = db.withTransaction {
        val plan = planDao.getPlanById(id) ?: return@withTransaction
        val previous = db.completionDao().get(id, date)?.completed ?: false
        planDao.updatePlan(plan.copy(isCompleted = completed, updatedAt = System.currentTimeMillis()))
        db.completionDao().put(PlanCompletion(id, date, completed))
        if (previous != completed) {
            val count = db.dailyCheckinDao().getByDate(date)?.completedCount ?: 0
            db.dailyCheckinDao().insert(DailyCheckin(date, (count + if (completed) 1 else -1).coerceAtLeast(0)))
            // Keep spent entries on undo. A spent reward becomes debt until re-completed,
            // so undo/re-complete cannot mint another spendable credit.
            db.creditDao().put(CreditEntry("plan:$id:$date", date, if (completed) 1 else 0))
        }
    }

    // ── 同步：保存计划及其提醒，返回 (planId, listOf<reminderId>) ──

    suspend fun savePlanWithReminders(
        plan: PlanEntity,
        reminders: List<ReminderEntity>
    ): Pair<Long, List<Long>> = db.withTransaction {
        require(plan.name.isNotBlank()) { "请输入计划名称" }
        require(plan.repeatMode in setOf("EVERY_DAY", "WEEKDAYS", "CUSTOM")) { "无效的重复模式" }
        if (plan.repeatMode == "CUSTOM") {
            val days = plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "请至少选择一个有效星期" }
        }
        require(reminders.all { it.hour in 0..23 && it.minute in 0..59 }) { "无效的提醒时间" }
        val existing = planDao.getPlanById(plan.id)
        val oldVersions = reminderDao.getRemindersByPlanId(plan.id).associate { it.id to it.scheduleVersion }
        val planId = if (existing == null) {
            planDao.insertPlan(plan)
        } else {
            planDao.updatePlan(plan.copy(createdAt = existing.createdAt))
            plan.id
        }

        // 删除旧提醒，重新插入
        reminderDao.deleteRemindersByPlanId(planId)
        val reminderIds = reminders.map { reminder ->
            reminderDao.insertReminder(reminder.copy(planId = planId,
                scheduleVersion = maxOf(reminder.scheduleVersion, oldVersions[reminder.id] ?: 0L) + 1))
        }
        setCompleted(planId, plan.isCompleted)
        Pair(planId, reminderIds)
    }
}
