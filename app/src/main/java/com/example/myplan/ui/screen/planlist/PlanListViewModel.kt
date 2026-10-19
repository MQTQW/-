package com.example.myplan.ui.screen.planlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.PlanActions
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
        viewModelScope.launch {
            combine(db.dailyCheckinDao().observeAll(), (application as com.example.myplan.MyPlanApp).dates.today) { _, _ -> Unit }
                .collect { computeStreak() }
        }
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

    val allPlans = repo.observeAllPlans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val actions = PlanActions(repo, scheduler)

    private fun runAction(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.e("PlanList", "Action failed", e)
            android.widget.Toast.makeText(getApplication(), e.message ?: "操作失败，请重试", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun togglePlanEnabled(plan: PlanWithReminders) {
        runAction { actions.setEnabled(plan.plan.id, !plan.plan.isEnabled) }
    }

    fun toggleComplete(plan: PlanWithReminders) {
        runAction { actions.setCompleted(plan.plan.id, !plan.plan.isCompleted) }
    }

    fun deletePlan(plan: PlanWithReminders) {
        undoJob?.cancel()
        val deletion = runAction { actions.delete(plan.plan) }
        pendingRestore = { deletion.join(); actions.save(plan.plan, plan.reminders); Unit }
        undoJob = viewModelScope.launch { delay(5000); pendingRestore = null }
    }

    fun undoDelete() {
        runAction {
            val restore = pendingRestore ?: return@runAction
            pendingRestore = null
            undoJob?.cancel()
            restore()
        }
    }
}
