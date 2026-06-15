package com.example.myplan.ui.screen.planlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlanListUiState(
    val plans: List<PlanWithReminders> = emptyList(),
    val filter: PlanFilter = PlanFilter.ALL
)

enum class PlanFilter { ALL, ACTIVE, COMPLETED }

class PlanListViewModel(application: Application) : AndroidViewModel(application) {

    private val db = PlanDatabase.getInstance(application)
    private val repo = PlanRepository(db)
    private val scheduler = AlarmScheduler(application)

    private val _filter = MutableStateFlow(PlanFilter.ALL)
    val filter: StateFlow<PlanFilter> = _filter.asStateFlow()

    // 连续打卡
    private val _streakDays = MutableStateFlow(0)
    val streakDays: StateFlow<Int> = _streakDays.asStateFlow()

    init {
        viewModelScope.launch { computeStreak() }
    }

    private suspend fun computeStreak() {
        val checkins = db.dailyCheckinDao().getAll()
        var streak = 0
        val today = java.time.LocalDate.now()
        for (i in 0..365) {
            val date = today.minusDays(i.toLong()).toString()
            if (checkins.any { it.date == date && it.completedCount > 0 }) streak++
            else break
        }
        _streakDays.value = streak
    }

    // 撤销机制
    private var pendingRestore: (suspend () -> Unit)? = null
    private var undoJob: Job? = null

    val uiState: StateFlow<PlanListUiState> = _filter.flatMapLatest { f ->
        (when (f) {
            PlanFilter.ALL -> repo.observeAllPlans()
            PlanFilter.ACTIVE -> repo.observeActivePlans()
            PlanFilter.COMPLETED -> repo.observeCompletedPlans()
        }).map { plans -> PlanListUiState(plans = plans, filter = f) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanListUiState())

    fun setFilter(filter: PlanFilter) { _filter.value = filter }

    fun togglePlanEnabled(plan: PlanWithReminders) {
        viewModelScope.launch {
            val updated = plan.plan.copy(isEnabled = !plan.plan.isEnabled)
            repo.updatePlan(updated)
            if (updated.isEnabled) {
                plan.reminders.filter { it.isEnabled }.forEach { rem ->
                    scheduler.scheduleAlarm(plan.plan.id, rem.id, rem.time)
                }
            } else {
                scheduler.cancelAllForPlan(plan.reminders.map { it.id })
            }
        }
    }

    fun toggleComplete(plan: PlanWithReminders) {
        viewModelScope.launch {
            val completed = !plan.plan.isCompleted
            // 只切换完成状态，不影响提醒开关
            repo.updatePlan(plan.plan.copy(isCompleted = completed, updatedAt = System.currentTimeMillis()))
            if (completed) {
                // 记录打卡
                val today = java.time.LocalDate.now().toString()
                val existing = db.dailyCheckinDao().getByDate(today)
                db.dailyCheckinDao().insert(
                    com.example.myplan.data.local.entity.DailyCheckin(
                        date = today,
                        completedCount = (existing?.completedCount ?: 0) + 1
                    )
                )
                computeStreak()
            }
        }
    }

    fun deletePlan(plan: PlanWithReminders) {
        // 保存恢复信息
        pendingRestore = {
            repo.savePlanWithReminders(plan.plan, plan.reminders)
            plan.reminders.filter { it.isEnabled }.forEach { rem ->
                scheduler.scheduleAlarm(plan.plan.id, rem.id, rem.time)
            }
        }
        // 删除
        viewModelScope.launch {
            scheduler.cancelAllForPlan(plan.reminders.map { it.id })
            repo.deletePlan(plan.plan)
        }
        // 5 秒后清除恢复
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(5000); pendingRestore = null
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            pendingRestore?.invoke()
            pendingRestore = null
            undoJob?.cancel()
        }
    }

}
