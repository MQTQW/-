package com.example.myplan.focus.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.FocusAppSafety
import com.example.myplan.focus.FocusEnforcement
import com.example.myplan.focus.FocusLockReason
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.DailyUsageEntity
import com.example.myplan.focus.data.repository.AppUsageRepository
import com.example.myplan.focus.data.repository.LimitRepository
import com.example.myplan.focus.data.repository.TaskRepository
import com.example.myplan.focus.service.UsageMonitorService
import com.example.myplan.focus.util.PermissionHelper
import com.example.myplan.focus.session.AndroidFocusClock
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.session.TimedUnlockEntity
import com.example.myplan.focus.session.TimedUnlockResult
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val todayUsage: List<DailyUsageEntity> = emptyList(),
    val limits: List<AppLimitEntity> = emptyList(),
    val uncompletedTaskCount: Int = 0,
    val isMonitoringActive: Boolean = false,
    val isLoading: Boolean = true,
    val focusSession: FocusSessionEntity? = null,
    val focusRemainingMillis: Long = 0,
    val focusPlans: List<PlanEntity> = emptyList(),
    val focusSteps: List<PlanStepEntity> = emptyList(),
    val focusDataLoaded: Boolean = false,
    val focusDate: String = java.time.LocalDate.now().toString(),
    val focusApps: List<Pair<String, String>> = emptyList(),
    val focusAppsLoaded: Boolean = false,
    val timedUnlocks: Map<String, Long> = emptyMap(),
    val redemptionApps: List<Pair<String, String>> = emptyList(),
    val focusBusy: Boolean = false,
    val focusMessage: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel @JvmOverloads constructor(application: Application,
    private val dates: com.example.myplan.data.DateProvider = (application as MyPlanApp).dates
) : AndroidViewModel(application) {

    private val app = application as MyPlanApp
    private val usageRepo = AppUsageRepository(application, app.focusDb)
    private val limitRepo = LimitRepository(app.focusDb)
    private val taskRepo = TaskRepository(app.focusDb)
    private val planDb = PlanDatabase.getInstance(app)
    private val planRepo = PlanRepository(planDb, dates)
    private val sessionRepo = app.focusSessionRepository
    private val unlockRepo = app.timedUnlockRepository
    private val clock = AndroidFocusClock(app)
    private var leases: List<TimedUnlockEntity> = emptyList()

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                combine(planRepo.observeAllPlans(), planDb.planStepDao().observeAll(), sessionRepo.observeSession()) {
                        plans, steps, _ -> plans to steps
                }.collect { (plans, steps) ->
                    val current = sessionRepo.reconcile()
                    _state.update { it.copy(
                        focusPlans = plans.map { row -> row.plan }.filter { p -> p.isEnabled && !p.isCompleted },
                        focusSteps = steps, focusSession = current, focusDataLoaded = true
                    ) }
                    refreshRemaining()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e("HomeViewModel", "Focus plans init failed", e)
                _state.update { it.copy(focusDataLoaded = true, focusMessage = "暂时无法读取专注计划，请退出页面后重试。") }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = FocusAppSafety.selectableApps(app, usageRepo.getInstalledApps())
                _state.update { it.copy(focusApps = apps, focusAppsLoaded = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e("HomeViewModel", "App list failed", e)
                _state.update { it.copy(focusAppsLoaded = true, focusMessage = "暂时无法读取应用列表，请退出专注页面后重试。") }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            unlockRepo.observeUnlocks().collect {
                leases = it
                refreshRemaining()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepo.reconcile()
            while (isActive) {
                refreshRemaining()
                val session = _state.value.focusSession
                if (session?.status == FocusSessionEntity.RUNNING && _state.value.focusRemainingMillis <= 0)
                    sessionRepo.reconcile()
                delay(1000)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                limitRepo.ensurePresetCategories()

                dates.today.flatMapLatest { usageRepo.getUsageForDate(it.toString()) }.collect { usageList ->
                    _state.update { it.copy(todayUsage = usageList, isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Usage init failed", e)
                _state.update { it.copy(isLoading = false) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                limitRepo.getAllLimits().collect { limits ->
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

    private fun refreshRemaining() {
        val now = clock.now()
        _state.update { state -> state.copy(
            focusDate = now.date,
            focusRemainingMillis = state.focusSession?.remainingAt(now) ?: 0,
            timedUnlocks = leases.mapNotNull { lease ->
                lease.remainingAt(now).takeIf { it > 0 }?.let { lease.packageName to it }
            }.toMap()
        ) }
    }

    suspend fun prepareFocusTarget(planId: Long, stepId: Long?): Boolean {
        try {
            val current = sessionRepo.current()
            val plan = planRepo.getPlanById(planId)
            val step = stepId?.let { planDb.planStepDao().getStep(planId, it) }
            val date = clock.now().date
            val message = when {
                current?.isUnfinished == true -> "已有一段专注待处理，请先确认完成或结束本次，再选择新的目标。"
                plan == null || !plan.isEnabled || plan.isCompleted -> "这条计划已完成或不可用，请重新选择。"
                stepId != null && (step == null || step.isDoneOn(plan.repeatMode, date)) ->
                    "这一步已完成或不可用，请重新选择。"
                else -> null
            }
            val targetSteps = if (message == null) planDb.planStepDao().getSteps(planId) else emptyList()
            _state.update { it.copy(focusMessage = message, focusSession = current, focusDate = date,
                focusPlans = if (message == null && plan != null) it.focusPlans.filter { p -> p.id != planId } + plan else it.focusPlans,
                focusSteps = if (message == null) it.focusSteps.filter { s -> s.planId != planId } + targetSteps else it.focusSteps
            ) }
            return message == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            Log.e("HomeViewModel", "Focus target lookup failed", e)
            _state.update { it.copy(focusMessage = "暂时无法读取专注目标，请重新选择。") }
            return false
        }
    }

    fun startFocus(planId: Long, minutes: Int, packages: Set<String>, stepId: Long? = null) = focusAction {
        check(PermissionHelper.hasUsageStatsPermission(app) && PermissionHelper.hasAccessibilityPermission(app)) {
            "请先开启使用情况访问和 MY PLAN 无障碍服务。"
        }
        val safePackages = FocusAppSafety.filterPackages(app, packages)
        require(safePackages.isNotEmpty()) { "请至少选择一个可限制的应用。" }
        UsageMonitorService.start(app)
        sessionRepo.start(planId, minutes, safePackages, stepId = stepId)
    }

    fun pauseFocus() = focusAction {
        _state.value.focusSession?.let { sessionRepo.pause(it.sessionId) }
    }

    fun resumeFocus() = focusAction {
        check(PermissionHelper.hasUsageStatsPermission(app) && PermissionHelper.hasAccessibilityPermission(app)) {
            "请先开启使用情况访问和 MY PLAN 无障碍服务。"
        }
        UsageMonitorService.start(app)
        _state.value.focusSession?.let { sessionRepo.resume(it.sessionId) }
    }

    fun cancelFocus() = focusAction {
        _state.value.focusSession?.let { sessionRepo.cancel(it.sessionId) }
    }

    fun completeFocus() = focusAction {
        val session = _state.value.focusSession ?: return@focusAction
        val done = PlanActions(planRepo, AlarmScheduler(app)).completeFocusSession(session.sessionId, sessionRepo)
        check(done) { if (session.stepId == null) "暂时无法完成，请确认计划仍存在且专注计时已结束。"
            else "暂时无法完成，请确认这一步仍有效且专注计时已结束。" }
        _state.update { it.copy(focusMessage = if (session.stepId != null)
            "已完成这一步。整条计划仍需单独打卡，步骤不发放积分。" else "已完成计划，积分已更新。") }
    }

    fun prepareRedemption() = focusAction {
        val blocked = FocusEnforcement(app).snapshot()
        val names = _state.value.focusApps.toMap()
        val apps = blocked.lockedPackages.filter { blocked.reason(it) == FocusLockReason.DAILY_LIMIT }
            .map { pkg -> pkg to (names[pkg] ?: usageRepo.getAppName(pkg)) }.sortedBy { it.second }
        _state.update { it.copy(redemptionApps = apps) }
    }

    fun redeem(packageName: String, requestId: String) = focusAction {
        check(PermissionHelper.hasUsageStatsPermission(app) && PermissionHelper.hasAccessibilityPermission(app)) {
            "请先开启使用情况访问和 MY PLAN 无障碍服务。"
        }
        val snapshot = FocusEnforcement(app).snapshot()
        check(snapshot.reason(packageName) != FocusLockReason.SESSION) {
            "该应用正在专注限制中，请先暂停或结束专注。"
        }
        if (packageName !in snapshot.timedUnlockedPackages) check(snapshot.reason(packageName) == FocusLockReason.DAILY_LIMIT) {
            "这个应用当前无需解锁，未扣除积分。"
        }
        when (val result = unlockRepo.redeem(packageName, requestId)) {
            is TimedUnlockResult.Granted -> {
                val remaining = result.lease.remainingAt(clock.now())
                _state.update { it.copy(focusMessage = if (remaining > 0)
                    "已解锁，剩余 ${focusCountdown(remaining)}，到期恢复原有限额。"
                    else "这次兑换已过期，未重复扣分。请关闭后重新兑换。") }
            }
            TimedUnlockResult.InsufficientCredits -> error("积分不足，完成计划后再来兑换。")
            TimedUnlockResult.BlockedByFocus -> error("该应用正在专注限制中，请先暂停或结束专注。")
        }
    }

    fun clearFocusMessage() { _state.update { it.copy(focusMessage = null) } }

    private fun focusAction(block: suspend () -> Unit) {
        if (_state.value.focusBusy) return
        _state.update { it.copy(focusBusy = true, focusMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                app.sendBroadcast(android.content.Intent(com.example.myplan.focus.util.FocusConstants.ACTION_USAGE_UPDATED)
                    .setPackage(app.packageName))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e("HomeViewModel", "Focus action failed", e)
                _state.update { it.copy(focusMessage = e.message?.takeIf { message -> message.length < 100 }
                    ?: "暂时没能完成操作，请再试一次。") }
            } finally { _state.update { it.copy(focusBusy = false) } }
        }
    }
}
