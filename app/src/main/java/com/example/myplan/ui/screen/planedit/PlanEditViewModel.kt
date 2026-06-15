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
    val time: Long = System.currentTimeMillis() + 3_600_000L,
    val repeatMode: String = "NONE",
    val isEnabled: Boolean = true,
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
            _uiState.value = PlanEditUiState(isEditMode = false, reminders = listOf(ReminderState()))
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val plan = repo.observePlan(planId).first()
                if (plan != null) {
                    _uiState.value = PlanEditUiState(
                        id = plan.plan.id, name = plan.plan.name, description = plan.plan.description ?: "",
                        category = plan.plan.category, isCompleted = plan.plan.isCompleted,
                        reminders = plan.reminders.map { ReminderState(id = it.id, time = it.time, repeatMode = it.repeatMode, isEnabled = it.isEnabled) },
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

    fun addReminder(hour: Int = 9, minute: Int = 0, dayOffset: Int = 0) {
        val time = computeTimestamp(hour, minute, dayOffset)
        _uiState.update { it.copy(reminders = it.reminders + ReminderState(time = time), isDirty = true) }
    }

    fun updateReminder(index: Int, hour: Int, minute: Int, dayOffset: Int = 0) {
        val newTime = computeTimestamp(hour, minute, dayOffset)
        _uiState.update { state ->
            val r = state.reminders.toMutableList()
            if (index in r.indices) r[index] = r[index].copy(time = newTime)
            state.copy(reminders = r, isDirty = true)
        }
    }

    private fun computeTimestamp(hour: Int, minute: Int, dayOffset: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun updateRepeatMode(index: Int, mode: String) { _uiState.update { state -> val r = state.reminders.toMutableList(); if (index in r.indices) r[index] = r[index].copy(repeatMode = mode); state.copy(reminders = r, isDirty = true) } }
    fun toggleReminder(index: Int) { _uiState.update { state -> val r = state.reminders.toMutableList(); if (index in r.indices) r[index] = r[index].copy(isEnabled = !r[index].isEnabled); state.copy(reminders = r, isDirty = true) } }
    fun removeReminder(index: Int) { _uiState.update { state -> val r = state.reminders.toMutableList(); if (index in r.indices) r.removeAt(index); state.copy(reminders = r, isDirty = true) } }

    fun save(onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val state = _uiState.value
                val plan = PlanEntity(id = state.id, name = state.name, description = state.description.ifBlank { null }, category = state.category, isEnabled = state.reminders.any { it.isEnabled }, isCompleted = state.isCompleted, updatedAt = System.currentTimeMillis())
                val reminders = state.reminders.map { ReminderEntity(id = it.id, planId = state.id, time = it.time, repeatMode = it.repeatMode, isEnabled = it.isEnabled) }
                repo.savePlanWithReminders(plan, reminders)
                state.reminders.forEach { rem -> scheduler.cancelAlarm(rem.id); if (rem.isEnabled) scheduler.scheduleAlarm(state.id, rem.id, rem.time) }
                if (state.isCompleted) state.reminders.forEach { scheduler.cancelAlarm(it.id) }
            }
            onComplete()
        }
    }
}
