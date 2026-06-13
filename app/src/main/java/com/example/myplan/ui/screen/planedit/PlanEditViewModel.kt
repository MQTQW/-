package com.example.myplan.ui.screen.planedit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ReminderState(
    val id: Long = 0,
    val time: Long = System.currentTimeMillis() + 3_600_000L,
    val repeatMode: String = "NONE",
    val isEnabled: Boolean = true,
    val timeText: String = ""
) {
    fun timeText(): String {
        val totalMinutes = (time / 60_000).toInt()
        val h = (totalMinutes / 60) % 24
        val m = totalMinutes % 60
        return "%02d:%02d".format(h, m)
    }
}

data class PlanEditUiState(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val category: String = "日常",
    val isCompleted: Boolean = false,
    val reminders: List<ReminderState> = emptyList(),
    val isEditMode: Boolean = false,
    val isDirty: Boolean = false
)

class PlanEditViewModel(application: Application) : AndroidViewModel(application) {

    private val db = PlanDatabase.getInstance(application)
    private val repo = PlanRepository(db)
    private val scheduler = AlarmScheduler(application)

    private val _uiState = MutableStateFlow(PlanEditUiState())
    val uiState: StateFlow<PlanEditUiState> = _uiState.asStateFlow()

    fun loadPlan(planId: Long?) {
        if (planId == null || planId == 0L) {
            // 新建模式
            _uiState.value = PlanEditUiState(
                isEditMode = false,
                reminders = listOf(ReminderState())
            )
        } else {
            // 编辑模式
            viewModelScope.launch {
                val plan = repo.observePlan(planId).first()
                if (plan != null) {
                    _uiState.value = PlanEditUiState(
                        id = plan.plan.id,
                        name = plan.plan.name,
                        description = plan.plan.description ?: "",
                        category = plan.plan.category,
                        isCompleted = plan.plan.isCompleted,
                        reminders = plan.reminders.map { rem ->
                            ReminderState(
                                id = rem.id,
                                time = rem.time,
                                repeatMode = rem.repeatMode,
                                isEnabled = rem.isEnabled
                            )
                        },
                        isEditMode = true
                    )
                }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, isDirty = true) }
    }

    fun updateDescription(desc: String) {
        _uiState.update { it.copy(description = desc, isDirty = true) }
    }

    fun updateCategory(cat: String) {
        _uiState.update { it.copy(category = cat, isDirty = true) }
    }

    fun toggleComplete() {
        _uiState.update { it.copy(isCompleted = !it.isCompleted, isDirty = true) }
    }

    fun addReminder(hour: Int = 9, minute: Int = 0) {
        val now = System.currentTimeMillis()
        val time = now + hour * 3_600_000L + minute * 60_000L
        _uiState.update {
            it.copy(
                reminders = it.reminders + ReminderState(time = time),
                isDirty = true
            )
        }
    }

    fun updateReminder(index: Int, hour: Int, minute: Int) {
        _uiState.update { state ->
            val reminders = state.reminders.toMutableList()
            if (index in reminders.indices) {
                val old = reminders[index]
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = old.time
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                reminders[index] = old.copy(time = cal.timeInMillis)
            }
            state.copy(reminders = reminders, isDirty = true)
        }
    }

    fun updateRepeatMode(index: Int, mode: String) {
        _uiState.update { state ->
            val reminders = state.reminders.toMutableList()
            if (index in reminders.indices) {
                reminders[index] = reminders[index].copy(repeatMode = mode)
            }
            state.copy(reminders = reminders, isDirty = true)
        }
    }

    fun toggleReminder(index: Int) {
        _uiState.update { state ->
            val reminders = state.reminders.toMutableList()
            if (index in reminders.indices) {
                reminders[index] = reminders[index].copy(isEnabled = !reminders[index].isEnabled)
            }
            state.copy(reminders = reminders, isDirty = true)
        }
    }

    fun removeReminder(index: Int) {
        _uiState.update { state ->
            state.copy(
                reminders = state.reminders.toMutableList().also { if (index in it.indices) it.removeAt(index) },
                isDirty = true
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val plan = PlanEntity(
                id = state.id,
                name = state.name,
                description = state.description.ifBlank { null },
                category = state.category,
                isEnabled = state.reminders.any { it.isEnabled },
                isCompleted = state.isCompleted,
                updatedAt = System.currentTimeMillis()
            )
            val reminders = state.reminders.map { rem ->
                ReminderEntity(
                    id = rem.id,
                    planId = state.id,
                    time = rem.time,
                    repeatMode = rem.repeatMode,
                    isEnabled = rem.isEnabled
                )
            }
            repo.savePlanWithReminders(plan, reminders)

            // 重新调度闹钟
            state.reminders.forEach { rem ->
                scheduler.cancelAlarm(rem.id)
                if (rem.isEnabled) {
                    scheduler.scheduleAlarm(state.id, rem.id, rem.time)
                }
            }

            // 如果计划被标记完成，取消所有闹钟
            if (state.isCompleted) {
                state.reminders.forEach { scheduler.cancelAlarm(it.id) }
            }
        }
    }
}
