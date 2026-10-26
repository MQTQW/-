package com.example.myplan.notification

import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.PlanStepEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.session.FocusSessionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by manual editing, voice creation, restore and receivers. */
data class DeletedPlanSnapshot(val plan: PlanEntity, val reminders: List<ReminderEntity>, val steps: List<PlanStepEntity>)

class PlanActions(private val repo: PlanRepository, private val scheduler: AlarmScheduler) {
    suspend fun save(plan: PlanEntity, reminders: List<ReminderEntity>, steps: List<PlanStepDraft>? = null): Long = mutex.withLock {
        val old = repo.getRemindersByPlanId(plan.id)
        val (id, _) = repo.savePlanWithReminders(plan, reminders, steps)
        if (plan.id > 0) scheduler.stopRinging(plan.id)
        old.forEach { scheduler.cancelAlarm(it.id) }
        try { syncLocked(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw AlarmSyncException(id, e) }
        id
    }

    suspend fun delete(plan: PlanEntity) = mutex.withLock {
        val reminders = repo.getRemindersByPlanId(plan.id)
        val steps = repo.getStepsForPlan(plan.id)
        repo.deletePlan(plan)
        scheduler.stopRinging(plan.id)
        reminders.forEach { scheduler.cancelAlarm(it.id) }
        DeletedPlanSnapshot(plan, reminders, steps)
    }

    suspend fun restoreDeleted(snapshot: DeletedPlanSnapshot): Long = mutex.withLock {
        val (id, _) = repo.restoreDeleted(snapshot.plan, snapshot.reminders, snapshot.steps)
        try { syncLocked(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw AlarmSyncException(id, e) }
        id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = mutex.withLock {
        val plan = repo.getPlanById(id) ?: return@withLock
        repo.updatePlan(plan.copy(isEnabled = enabled, updatedAt = maxOf(System.currentTimeMillis(), plan.updatedAt + 1)))
        if (!enabled) scheduler.stopRinging(id)
        syncLocked(id)
    }

    suspend fun setCompleted(id: Long, completed: Boolean) = mutex.withLock {
        repo.setCompleted(id, completed)
        if (completed) scheduler.stopRinging(id)
        if (repo.getPlanById(id)?.repeatMode == "ONCE") syncLocked(id)
    }

    /** A launcher may send an old PendingIntent after an edit, deletion or midnight. */
    suspend fun setCompletedIfUnchanged(id: Long, completed: Boolean, date: String,
        expectedUpdatedAt: Long): PlanEntity? = mutex.withLock {
        if (java.time.LocalDate.now().toString() != date) return@withLock null
        val plan = repo.getPlanById(id) ?: return@withLock null
        if (plan.updatedAt != expectedUpdatedAt || plan.isCompleted == completed || !plan.isEnabled) return@withLock null
        val row = com.example.myplan.data.local.relation.PlanWithReminders(plan, repo.getRemindersByPlanId(id))
        if (!com.example.myplan.data.isScheduledOn(row, java.time.LocalDate.parse(date))) return@withLock null
        repo.setCompleted(id, completed, date)
        if (completed) scheduler.stopRinging(id)
        if (plan.repeatMode == "ONCE") syncLocked(id)
        repo.getPlanById(id)
    }

    suspend fun sync(id: Long) = mutex.withLock { syncLocked(id) }

    /** Keep plan completion, its existing reward and session confirmation in one transaction. */
    suspend fun completeFocusSession(sessionId: String, sessions: FocusSessionRepository): Boolean = mutex.withLock {
        val finished = sessions.confirmCompletion(sessionId) { id, date -> repo.setCompleted(id, true, date) }
            ?: return@withLock false
        if (finished.stepId == null) {
            scheduler.stopRinging(finished.planId)
            if (repo.getPlanById(finished.planId)?.repeatMode == "ONCE") syncLocked(finished.planId)
        }
        true
    }

    suspend fun restoreAll() = mutex.withLock {
        repo.getAllPlans().forEach { plan ->
            try { syncLocked(plan.id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("PlanActions", "Cannot restore plan ${plan.id}", e) }
        }
    }

    private suspend fun syncLocked(id: Long) {
        val plan = repo.getPlanById(id) ?: return
        repo.getRemindersByPlanId(id).forEach { reminder ->
            scheduler.cancelAlarm(reminder.id)
            if (plan.isEnabled && reminder.isEnabled) schedule(plan, reminder)
        }
    }

    suspend fun fire(planId: Long, reminderId: Long, version: Long = 0, notify: (PlanEntity) -> Unit): Boolean = mutex.withLock {
        val reminder = repo.getReminderById(reminderId) ?: return@withLock false
        val plan = repo.getPlanById(planId) ?: return@withLock false
        if (reminder.planId != plan.id || !reminder.isEnabled || !plan.isEnabled) return@withLock false
        if (reminder.scheduleVersion != version) return@withLock false
        if (plan.repeatMode == "ONCE") {
            if (plan.isCompleted) return@withLock false
            // Consume this delivery before posting; repeated broadcasts cannot ring twice.
            repo.updateReminder(reminder.copy(scheduleVersion = version + 1))
            notify(plan)
            return@withLock true
        }
        // Restore repetition even if posting the current notification fails.
        try { schedule(plan, reminder) }
        catch (e: Exception) { android.util.Log.e("PlanActions", "Rescheduling failed", e) }
        notify(plan)
        true
    }

    fun schedule(plan: PlanEntity, reminder: ReminderEntity) {
        val next = scheduler.nextFireTime(plan, reminder) ?: return
        scheduler.scheduleAlarm(plan.id, reminder.id, next, plan.name, plan.description,
            plan.repeatMode, plan.customDays, reminder.hour, reminder.minute, reminder.scheduleVersion)
    }

    companion object { private val mutex = Mutex() }
}

class AlarmSyncException(val planId: Long, cause: Exception) :
    IllegalStateException("计划已保存，但提醒设置失败，请检查系统提醒权限", cause)
