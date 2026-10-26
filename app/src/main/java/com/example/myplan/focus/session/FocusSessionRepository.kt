package com.example.myplan.focus.session

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import java.util.UUID

class FocusSessionRepository(private val db: PlanDatabase, private val clock: FocusClock) {
    private val dao = db.focusSessionDao()

    fun observeSession() = dao.observeSession()
    suspend fun current(): FocusSessionEntity? = reconcile()

    suspend fun reconcile(): FocusSessionEntity? = db.withTransaction { reconcileLocked(clock.now()) }

    internal suspend fun reconcileLocked(time: FocusTime): FocusSessionEntity? {
        val session = dao.current() ?: return null
        if (!session.isUnfinished) return session
        val plan = db.planDao().getPlanById(session.planId)
        val stepInvalid = session.stepId?.let { id ->
            val step = db.planStepDao().getStep(session.planId, id)
            step == null || plan == null || step.isDoneOn(plan.repeatMode, time.date)
        } ?: false
        val invalid = time.date != session.date || plan == null || !plan.isEnabled || plan.isCompleted ||
            plan.updatedAt != session.planUpdatedAt || stepInvalid
        val next = when {
            invalid -> session.copy(status = FocusSessionEntity.CANCELLED, updatedAt = time.wallTimeMillis)
            session.status == FocusSessionEntity.RUNNING && (time.bootCount != session.bootCount ||
                time.elapsedRealtimeMillis < session.anchorElapsedRealtime) ->
                session.copy(status = FocusSessionEntity.PAUSED, updatedAt = time.wallTimeMillis,
                    anchorElapsedRealtime = time.elapsedRealtimeMillis, bootCount = time.bootCount)
            session.status == FocusSessionEntity.RUNNING && session.remainingAt(time) == 0L ->
                session.copy(status = FocusSessionEntity.READY, remainingMillis = 0, updatedAt = time.wallTimeMillis)
            else -> session
        }
        if (next != session) dao.put(next)
        return next
    }

    suspend fun start(planId: Long, minutes: Int, packages: Set<String>, requestId: String = UUID.randomUUID().toString(),
        stepId: Long? = null): FocusSessionEntity = db.withTransaction {
        require(minutes in 1..180) { "专注时长需在 1 到 180 分钟之间" }
        require(requestId.isNotBlank() && requestId.length <= 255) { "缺少专注请求标识" }
        require(packages.all { validPackage(it) }) { "包含无效应用" }
        val time = clock.now()
        val previous = reconcileLocked(time)
        if (previous?.sessionId == requestId) {
            require(previous.planId == planId && previous.stepId == stepId) { "专注请求与计划步骤不一致" }
            return@withTransaction previous
        }
        check(previous?.isUnfinished != true) { "请先结束当前专注" }
        val plan = db.planDao().getPlanById(planId)
        require(plan != null && plan.isEnabled && !plan.isCompleted) { "请选择尚未完成的有效计划" }
        val step = stepId?.let { db.planStepDao().getStep(planId, it) }
        require(stepId == null || (step != null && !step.isDoneOn(plan.repeatMode, time.date))) {
            "请选择该计划尚未完成的步骤"
        }
        val duration = minutes * 60_000L
        FocusSessionEntity(sessionId = requestId, planId = planId, planName = plan.name,
            planUpdatedAt = plan.updatedAt, date = time.date, status = FocusSessionEntity.RUNNING,
            durationMillis = duration, remainingMillis = duration,
            blockedPackages = packages.sorted().joinToString("\n"), startedAt = time.wallTimeMillis,
            updatedAt = time.wallTimeMillis, anchorElapsedRealtime = time.elapsedRealtimeMillis,
            bootCount = time.bootCount, stepId = step?.id, stepTitle = step?.title).also { dao.put(it) }
    }

    suspend fun pause(sessionId: String): FocusSessionEntity? = db.withTransaction {
        val time = clock.now()
        val session = reconcileLocked(time)?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        if (session.status != FocusSessionEntity.RUNNING) return@withTransaction session
        session.copy(status = FocusSessionEntity.PAUSED, remainingMillis = session.remainingAt(time),
            updatedAt = time.wallTimeMillis, anchorElapsedRealtime = time.elapsedRealtimeMillis).also { dao.put(it) }
    }

    suspend fun resume(sessionId: String): FocusSessionEntity? = db.withTransaction {
        val time = clock.now()
        val session = reconcileLocked(time)?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        if (session.status != FocusSessionEntity.PAUSED) return@withTransaction session
        session.copy(status = FocusSessionEntity.RUNNING, updatedAt = time.wallTimeMillis,
            anchorElapsedRealtime = time.elapsedRealtimeMillis, bootCount = time.bootCount).also { dao.put(it) }
    }

    suspend fun cancel(sessionId: String): FocusSessionEntity? = db.withTransaction {
        val time = clock.now()
        val session = reconcileLocked(time)?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        if (!session.isUnfinished) return@withTransaction session
        session.copy(status = FocusSessionEntity.CANCELLED, remainingMillis = session.remainingAt(time),
            updatedAt = time.wallTimeMillis).also { dao.put(it) }
    }

    /** Caller must hold PlanActions' mutation mutex before entering this transaction. */
    suspend fun confirmCompletion(sessionId: String,
        completeStep: suspend (Long, Long, String) -> Unit = { planId, stepId, date ->
            db.planStepDao().setCompleted(planId, stepId, true, date)
        },
        completePlan: suspend (Long, String) -> Unit): FocusSessionEntity? = db.withTransaction {
        val time = clock.now()
        val session = reconcileLocked(time)?.takeIf {
            it.sessionId == sessionId && it.status == FocusSessionEntity.READY
        } ?: return@withTransaction null
        if (session.stepId != null) {
            completeStep(session.planId, session.stepId, session.date)
            val plan = db.planDao().getPlanById(session.planId)
            val step = db.planStepDao().getStep(session.planId, session.stepId)
            check(plan != null && step?.isDoneOn(plan.repeatMode, session.date) == true) {
                "步骤未完成，专注记录未确认"
            }
        } else {
            completePlan(session.planId, session.date)
            check(db.planDao().getPlanById(session.planId)?.isCompleted == true) { "计划未完成，专注记录未确认" }
        }
        session.copy(status = FocusSessionEntity.COMPLETED, updatedAt = time.wallTimeMillis).also { dao.put(it) }
    }

    companion object {
        internal fun validPackage(value: String) = value.isNotBlank() && value.length <= 255 &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }
}
