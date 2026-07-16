package com.example.myplan.focus.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.MyPlanApp
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
    val lockedApps: List<DailyUsageEntity> = emptyList(),
    val uncompletedTaskCount: Int = 0,
    val isMonitoringActive: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: MyPlanApp
    private val usageRepo: AppUsageRepository
    private val limitRepo: LimitRepository
    private val taskRepo: TaskRepository

    init {
        app = try { application as MyPlanApp } catch (e: Exception) {
            Log.e("HomeViewModel", "Application cast failed", e)
            throw e
        }
        usageRepo = AppUsageRepository(application, app.focusDb)
        limitRepo = LimitRepository(app.focusDb)
        taskRepo = TaskRepository(app.focusDb)
    }

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                limitRepo.ensurePresetCategories()

                val today = usageRepo.getToday()
                usageRepo.getUsageForDate(today).collect { usageList ->
                    val locked = usageList.filter { it.isLocked }
                    _state.update { it.copy(
                        todayUsage = usageList,
                        lockedApps = locked,
                        isLoading = false
                    )}
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Init failed", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
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
    }

    fun startMonitoring() {
        try {
            UsageMonitorService.start(getApplication())
            _state.update { it.copy(isMonitoringActive = true) }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "startMonitoring failed", e)
            _state.update { it.copy(error = "无法启动监控: ${e.message}") }
        }
    }

    fun checkPermissions(): Boolean {
        return try {
            val ctx = getApplication<Application>()
            PermissionHelper.hasUsageStatsPermission(ctx) &&
                    PermissionHelper.hasAccessibilityPermission(ctx) &&
                    PermissionHelper.hasOverlayPermission(ctx)
        } catch (e: Exception) {
            false
        }
    }
}
