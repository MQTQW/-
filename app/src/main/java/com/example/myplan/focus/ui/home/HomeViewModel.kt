package com.example.myplan.focus.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.MyPlanApp
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.data.repository.TaskRepository
import com.example.myplan.focus.service.UsageMonitorService
import com.example.myplan.focus.util.PermissionHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val todayUsage: List<DailyUsageEntity> = emptyList(),
    val lockedApps: List<DailyUsageEntity> = emptyList(),
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
        viewModelScope.launch {
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
        }

        viewModelScope.launch {
            taskRepo.getUncompletedCount().collect { count ->
                _state.update { it.copy(uncompletedTaskCount = count) }
            }
        }
    }

    fun startMonitoring() {
        UsageMonitorService.start(getApplication())
        _state.update { it.copy(isMonitoringActive = true) }
    }

    fun checkPermissions(): Boolean {
        val ctx = getApplication<Application>()
        return PermissionHelper.hasUsageStatsPermission(ctx) &&
                PermissionHelper.hasAccessibilityPermission(ctx) &&
                PermissionHelper.hasOverlayPermission(ctx)
    }
}
