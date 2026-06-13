package com.example.myplan.ui.screen.planlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    val uiState: StateFlow<PlanListUiState> = _filter.flatMapLatest { filter ->
        val flow = when (filter) {
            PlanFilter.ALL -> repo.observeAllPlans()
            PlanFilter.ACTIVE -> repo.observeActivePlans()
            PlanFilter.COMPLETED -> repo.observeCompletedPlans()
        }
        flow.map { plans -> PlanListUiState(plans = plans, filter = filter) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlanListUiState()
    )

    fun setFilter(filter: PlanFilter) {
        _filter.value = filter
    }

    fun togglePlanEnabled(plan: PlanWithReminders) {
        viewModelScope.launch {
            val updated = plan.plan.copy(isEnabled = !plan.plan.isEnabled)
            repo.updatePlan(updated)

            if (updated.isEnabled) {
                plan.reminders.filter { it.isEnabled }.forEach { rem ->
                    scheduler.scheduleAlarm(plan.plan.id, rem.id, rem.time)
                }
            } else {
                scheduler.cancelAllForPlan(plan.plan.id, plan.reminders.map { it.id })
            }
        }
    }

    fun toggleComplete(plan: PlanWithReminders) {
        viewModelScope.launch {
            val completed = !plan.plan.isCompleted
            val updated = plan.plan.copy(
                isCompleted = completed,
                isEnabled = if (completed) false else plan.plan.isEnabled
            )
            repo.updatePlan(updated)

            if (completed) {
                scheduler.cancelAllForPlan(plan.plan.id, plan.reminders.map { it.id })
            }
        }
    }

    fun requestDelete(plan: PlanWithReminders, onConfirm: () -> Unit) {
        onConfirm()
    }

    fun deletePlan(plan: PlanWithReminders) {
        viewModelScope.launch {
            scheduler.cancelAllForPlan(plan.plan.id, plan.reminders.map { it.id })
            repo.deletePlan(plan.plan)
        }
    }
}
