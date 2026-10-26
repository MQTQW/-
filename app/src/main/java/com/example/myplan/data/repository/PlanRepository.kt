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
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.PlanStepEntity

class PlanRepository(private val db: PlanDatabase, private val dates: DateProvider = DateProvider()) {

    private val planDao = db.planDao()
    private val reminderDao = db.reminderDao()

    suspend fun getStepsForPlan(planId: Long) = db.planStepDao().getSteps(planId)

    suspend fun restoreDeleted(plan: PlanEntity, reminders: List<ReminderEntity>,
        steps: List<PlanStepEntity>): Pair<Long, List<Long>> = db.withTransaction {
        check(planDao.getPlanById(plan.id) == null) { "这个计划已经恢复，请刷新后查看" }
        check(plan.creationToken == null || planDao.getByCreationToken(plan.creationToken) == null) { "这个计划已经恢复" }
        require(steps.size <= 8 && steps.all { it.planId == plan.id }) { "步骤恢复数据不完整" }
        val saved = savePlanWithReminders(plan, reminders)
        steps.forEach { db.planStepDao().insert(it.copy(planId = saved.first)) }
        saved
    }

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
    suspend fun getByCreationToken(token: String): PlanEntity? = planDao.getByCreationToken(token)
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
        recordCompletion(id, completed, date, System.currentTimeMillis())
    }

    private suspend fun recordCompletion(id: Long, completed: Boolean, date: String, updatedAt: Long) {
        val plan = planDao.getPlanById(id) ?: return
        val firstCompletion = if (plan.repeatMode == "ONCE") db.completionDao().firstForPlan(id) else null
        val completionDate = firstCompletion?.date ?: date
        val previous = db.completionDao().get(id, completionDate)?.completed ?: false
        planDao.updatePlan(plan.copy(isCompleted = completed, updatedAt = updatedAt))
        if (plan.repeatMode == "ONCE" && !completed && firstCompletion == null) return
        db.completionDao().put(PlanCompletion(id, completionDate, completed))
        if (previous != completed) {
            val count = db.dailyCheckinDao().getByDate(completionDate)?.completedCount ?: 0
            db.dailyCheckinDao().insert(DailyCheckin(completionDate, (count + if (completed) 1 else -1).coerceAtLeast(0)))
            // Keep spent entries on undo. A spent reward becomes debt until re-completed,
            // so undo/re-complete cannot mint another spendable credit.
            db.creditDao().put(CreditEntry("plan:$id:$completionDate", completionDate, if (completed) 1 else 0))
        }
    }

    // ── 同步：保存计划及其提醒，返回 (planId, listOf<reminderId>) ──

    suspend fun savePlanWithReminders(
        plan: PlanEntity,
        reminders: List<ReminderEntity>,
        steps: List<PlanStepDraft>? = null
    ): Pair<Long, List<Long>> = db.withTransaction {
        require(plan.name.isNotBlank()) { "请输入计划名称" }
        require(plan.suggestedFocusMinutes in setOf(15, 25, 45)) { "请选择 15、25 或 45 分钟专注" }
        steps?.let { drafts ->
            require(drafts.size <= 8) { "每个计划最多添加 8 个步骤" }
            require(drafts.all { it.title.trim().isNotEmpty() && it.title.trim().length <= 120 }) { "步骤标题需要 1 至 120 个字" }
            require(drafts.all { it.focusMinutes in setOf(15, 25, 45) }) { "步骤专注时长无效" }
            val ids = drafts.filter { it.id != 0L }.map { it.id }
            require(ids.distinct().size == ids.size) { "步骤不能重复" }
        }
        require(plan.repeatMode in setOf("ONCE", "EVERY_DAY", "WEEKDAYS", "CUSTOM")) { "无效的重复模式" }
        if (plan.repeatMode == "ONCE") {
            require((plan.oneTimeAt ?: 0) > 0 && reminders.size == 1) { "一次性计划需要一个日期和提醒时间" }
        }
        if (plan.repeatMode == "CUSTOM") {
            val days = plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "请至少选择一个有效星期" }
        }
        require(reminders.all { it.hour in 0..23 && it.minute in 0..59 }) { "无效的提醒时间" }
        if (plan.id == 0L && plan.creationToken != null) {
            planDao.getByCreationToken(plan.creationToken)?.let {
                return@withTransaction Pair(it.id, reminderDao.getRemindersByPlanId(it.id).map { r -> r.id })
            }
        }
        val existing = planDao.getPlanById(plan.id)
        val revision = maxOf(plan.updatedAt, System.currentTimeMillis(), (existing?.updatedAt ?: 0L) + 1)
        val oldSteps = db.planStepDao().getSteps(plan.id).associateBy { it.id }
        require(steps == null || steps.all { it.id == 0L || oldSteps.containsKey(it.id) }) { "步骤不属于此计划" }
        val oldVersions = reminderDao.getRemindersByPlanId(plan.id).associate { it.id to it.scheduleVersion }
        val planId = if (existing == null) {
            planDao.insertPlan(plan.copy(updatedAt = revision))
        } else {
            planDao.updatePlan(plan.copy(createdAt = existing.createdAt, creationToken = existing.creationToken, updatedAt = revision))
            plan.id
        }

        // 删除旧提醒，重新插入
        reminderDao.deleteRemindersByPlanId(planId)
        val reminderIds = reminders.map { reminder ->
            reminderDao.insertReminder(reminder.copy(planId = planId,
                scheduleVersion = maxOf(reminder.scheduleVersion, oldVersions[reminder.id] ?: 0L) + 1))
        }
        if (steps != null) {
            db.planStepDao().deleteForPlan(planId)
            steps.forEachIndexed { index, draft ->
                val old = oldSteps[draft.id]
                db.planStepDao().insert(PlanStepEntity(
                    id = draft.id, planId = planId, position = index, title = draft.title.trim(),
                    focusMinutes = draft.focusMinutes, isCompleted = old?.isCompleted ?: false,
                    completedDate = old?.completedDate
                ))
            }
        }
        recordCompletion(planId, plan.isCompleted, dates.currentDate().toString(), revision)
        Pair(planId, reminderIds)
    }
}
