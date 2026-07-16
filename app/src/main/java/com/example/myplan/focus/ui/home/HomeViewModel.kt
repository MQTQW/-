package com.example.myplan.focus.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.data.repository.TaskRepository
import com.example.myplan.focus.service.UsageMonitorService
import com.example.myplan.focus.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val todayUsage: List<DailyUsageEntity> = emptyList(),
    val limits: List<AppLimitEntity> = emptyList(),
    val uncompletedTaskCount: Int = 0,
    val isMonitoringActive: Boolean = false,
    val isLoading: Boolean = true
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyPlanApp
    private val usageRepo = AppUsageRepository(application, app.focusDb)
    private val limitRepo = LimitRepository(app.focusDb)
    private val taskRepo = TaskRepository(app.focusDb)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                limitRepo.ensurePresetCategories()

                val today = usageRepo.getToday()
                usageRepo.getUsageForDate(today).collect { usageList ->
                    _state.update { it.copy(todayUsage = usageList, isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Usage init failed", e)
                _state.update { it.copy(isLoading = false) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                limitRepo.getAllAppLimits().collect { limits ->
                    _state.update { it.copy(limits = limits) }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Limits init failed", e)
            }
        }

        viewModelScope.launch {
            try {
                taskRepo.getUncompletedCount().collect { count ->
                    _state.update { it.copy(uncompletedTaskCount = count) }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Task count failed", e)
            }
        }

        try {
            UsageMonitorService.start(application)
            _state.update { it.copy(isMonitoringActive = true) }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Monitor start failed", e)
        }
    }

    fun toggleLimit(limit: AppLimitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            limitRepo.setLimitEnabled(limit.id, !limit.isEnabled)
        }
    }

    fun deleteLimit(limit: AppLimitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            limitRepo.deleteLimit(limit.id)
        }
    }
}
