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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReminderState(
    val id: Long = 0,
    val hour: Int = 9,
    val minute: Int = 0,
    val isEnabled: Boolean = true,
) {
    fun timeText(): String = "%02d:%02d".format(hour, minute)
}

data class PlanEditUiState(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val category: String = "日常",
    val isCompleted: Boolean = false,
    val repeatMode: String = "EVERY_DAY",      // EVERY_DAY | WEEKDAYS | CUSTOM
    val customDays: Set<Int> = setOf(1, 2, 3, 4, 5),  // 1=Mon..7=Sun
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
            _uiState.value = PlanEditUiState(isEditMode = false, reminders = listOf(ReminderState()))
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val plan = repo.observePlan(planId).first()
                if (plan != null) {
                    _uiState.value = PlanEditUiState(
                        id = plan.plan.id, name = plan.plan.name, description = plan.plan.description ?: "",
                        category = plan.plan.category, isCompleted = plan.plan.isCompleted,
                        repeatMode = plan.plan.repeatMode,
                        customDays = plan.plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) },
                        reminders = plan.reminders.map { ReminderState(id = it.id, hour = it.hour, minute = it.minute, isEnabled = it.isEnabled) },
                        isEditMode = true
                    )
                }
            }
        }
    }

    fun updateName(name: String) { _uiState.update { it.copy(name = name, isDirty = true) } }
    fun updateDescription(desc: String) { _uiState.update { it.copy(description = desc, isDirty = true) } }
    fun updateCategory(cat: String) { _uiState.update { it.copy(category = cat, isDirty = true) } }
    fun toggleComplete() { _uiState.update { it.copy(isCompleted = !it.isCompleted, isDirty = true) } }

    fun updateRepeatMode(mode: String) {
        _uiState.update { state ->
            val days = when (mode) {
                "WEEKDAYS" -> setOf(1, 2, 3, 4, 5)
                "CUSTOM" -> state.customDays.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) }
                else -> setOf(1, 2, 3, 4, 5, 6, 7)
            }
            state.copy(repeatMode = mode, customDays = days, isDirty = true)
        }
    }

    fun toggleCustomDay(day: Int) {
        _uiState.update { state ->
            val days = state.customDays.toMutableSet()
            if (days.contains(day)) days.remove(day) else days.add(day)
            state.copy(customDays = days, isDirty = true)
        }
    }

    fun addReminder(hour: Int = 9, minute: Int = 0) {
        _uiState.update { it.copy(reminders = it.reminders + ReminderState(hour = hour, minute = minute), isDirty = true) }
    }

    fun updateReminder(index: Int, hour: Int, minute: Int) {
        _uiState.update { state ->
            val r = state.reminders.toMutableList()
            if (index in r.indices) r[index] = r[index].copy(hour = hour, minute = minute)
            state.copy(reminders = r, isDirty = true)
        }
    }

    fun toggleReminder(index: Int) { _uiState.update { state -> val r = state.reminders.toMutableList(); if (index in r.indices) r[index] = r[index].copy(isEnabled = !r[index].isEnabled); state.copy(reminders = r, isDirty = true) } }
    fun removeReminder(index: Int) { _uiState.update { state -> val r = state.reminders.toMutableList(); if (index in r.indices) r.removeAt(index); state.copy(reminders = r, isDirty = true) } }

    fun save(onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val state = _uiState.value
                val plan = PlanEntity(
                    id = state.id,
                    name = state.name,
                    description = state.description.ifBlank { null },
                    category = state.category,
                    isEnabled = state.reminders.any { it.isEnabled },
                    isCompleted = state.isCompleted,
                    repeatMode = state.repeatMode,
                    customDays = state.customDays.sorted().joinToString(","),
                    updatedAt = System.currentTimeMillis()
                )
                val reminders = state.reminders.map {
                    ReminderEntity(id = it.id, planId = state.id, hour = it.hour, minute = it.minute, isEnabled = it.isEnabled)
                }

                // 保存并获取数据库生成的真实 ID
                val (realPlanId, realReminderIds) = repo.savePlanWithReminders(plan, reminders)

                // 用真实 ID 重新调度所有闹钟
                state.reminders.forEachIndexed { i, rem ->
                    val realRemId = realReminderIds.getOrNull(i) ?: return@forEachIndexed
                    scheduler.cancelAlarm(rem.id)
                    if (rem.isEnabled) {
                        val nextTime = scheduler.computeNextFireTime(rem.hour, rem.minute, state.repeatMode, state.customDays)
                        if (nextTime != null) scheduler.scheduleAlarm(
                            planId = realPlanId, reminderId = realRemId, triggerTimeMillis = nextTime,
                            planName = state.name, planDescription = state.description.ifBlank { null },
                            repeatMode = state.repeatMode, customDays = state.customDays.sorted().joinToString(","),
                            hour = rem.hour, minute = rem.minute,
                        )
                    }
                }
                if (state.isCompleted) realReminderIds.forEach { scheduler.cancelAlarm(it) }
            }
            onComplete()
        }
    }
}
