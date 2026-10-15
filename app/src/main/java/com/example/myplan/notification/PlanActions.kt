package com.example.myplan.notification

import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by manual editing, voice creation, restore and receivers. */
class PlanActions(private val repo: PlanRepository, private val scheduler: AlarmScheduler) {
    suspend fun save(plan: PlanEntity, reminders: List<ReminderEntity>): Long = mutex.withLock {
        val old = repo.getRemindersByPlanId(plan.id)
        val (id, _) = repo.savePlanWithReminders(plan, reminders)
        old.forEach { scheduler.cancelAlarm(it.id) }
        try { sync(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw AlarmSyncException(id, e) }
        id
    }

    suspend fun delete(plan: PlanEntity) = mutex.withLock {
        val reminders = repo.getRemindersByPlanId(plan.id)
        repo.deletePlan(plan)
        reminders.forEach { scheduler.cancelAlarm(it.id) }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = mutex.withLock {
        val plan = repo.getPlanById(id) ?: return@withLock
        repo.updatePlan(plan.copy(isEnabled = enabled))
        sync(id)
    }

    suspend fun sync(id: Long) {
        val plan = repo.getPlanById(id) ?: return
        repo.getRemindersByPlanId(id).forEach { reminder ->
            scheduler.cancelAlarm(reminder.id)
            if (plan.isEnabled && reminder.isEnabled) schedule(plan, reminder)
        }
    }

    suspend fun fire(planId: Long, reminderId: Long, notify: (PlanEntity) -> Unit): Boolean = mutex.withLock {
        val reminder = repo.getReminderById(reminderId) ?: return@withLock false
        val plan = repo.getPlanById(planId) ?: return@withLock false
        if (reminder.planId != plan.id || !reminder.isEnabled || !plan.isEnabled) return@withLock false
        // Restore repetition even if posting the current notification fails.
        try { schedule(plan, reminder) }
        catch (e: Exception) { android.util.Log.e("PlanActions", "Rescheduling failed", e) }
        notify(plan)
        true
    }

    fun schedule(plan: PlanEntity, reminder: ReminderEntity) {
        val next = scheduler.nextFireTime(plan, reminder) ?: return
        scheduler.scheduleAlarm(plan.id, reminder.id, next, plan.name, plan.description,
            plan.repeatMode, plan.customDays, reminder.hour, reminder.minute)
    }

    companion object { val mutex = Mutex() }
}

class AlarmSyncException(val planId: Long, cause: Exception) :
    IllegalStateException("计划已保存，但提醒设置失败，请检查系统提醒权限", cause)
