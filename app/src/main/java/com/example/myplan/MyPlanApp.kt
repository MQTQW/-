package com.example.myplan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.notification.NotificationHelper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.focus.CreditRepository
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.DateProvider
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.widget.PlanWidgetProvider
import kotlinx.coroutines.flow.debounce
import com.example.myplan.focus.session.AndroidFocusClock
import com.example.myplan.focus.session.FocusSessionRepository
import com.example.myplan.focus.session.TimedUnlockRepository

class MyPlanApp : Application() {

    // Focus: global unlock credits pool (reactive)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var widgetJob: Job? = null
    val dates = DateProvider()
    val creditRepository by lazy { CreditRepository(PlanDatabase.getInstance(this), dates) }
    val focusSessionRepository by lazy { FocusSessionRepository(PlanDatabase.getInstance(this), AndroidFocusClock(this)) }
    val timedUnlockRepository by lazy { TimedUnlockRepository(PlanDatabase.getInstance(this), AndroidFocusClock(this)) }
    val unlockCredits: StateFlow<Int> by lazy {
        creditRepository.balance.stateIn(appScope, SharingStarted.Eagerly, 0)
    }

    // Focus: lazy DB singleton
    val focusDb: FocusDatabase by lazy { FocusDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.myplan.notification.AlarmDiagnostics.record(this, "process_created")

        // Existing notification channel
        NotificationHelper.createChannel(this)

        // Focus notification channels
        createFocusChannels()
        com.example.myplan.quickadd.QuickCaptureNotifications.refreshEntry(this)
        refreshWidgetObservation()
        appScope.launch {
            while (isActive) {
                try {
                    DailyRollover.ensureToday(this@MyPlanApp)
                    PlanWidgetProvider.updateAll(this@MyPlanApp)
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("MyPlanApp", "Daily recovery failed", e) }
                delay(60_000)
            }
        }
    }

    /** Only observe Room while the user has a widget on their launcher. */
    @Synchronized
    fun refreshWidgetObservation() {
        if (!PlanWidgetProvider.hasWidgets(this)) {
            widgetJob?.cancel()
            widgetJob = null
        } else if (widgetJob?.isActive != true) {
            widgetJob = appScope.launch {
                @OptIn(FlowPreview::class)
                PlanRepository(PlanDatabase.getInstance(this@MyPlanApp)).observeAllPlans().debounce(150).collect {
                    try { PlanWidgetProvider.updateAll(this@MyPlanApp) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { android.util.Log.e("PlanWidget", "Widget refresh failed", e) }
                }
            }
        }
    }

    private fun createFocusChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val monitorChannel = NotificationChannel(
            CHANNEL_FOCUS_MONITOR,
            "专注监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "应用使用时长监控状态"
        }

        val warningChannel = NotificationChannel(
            CHANNEL_FOCUS_WARNING,
            "专注提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "应用限额警告和锁定通知"
        }

        nm.createNotificationChannel(monitorChannel)
        nm.createNotificationChannel(warningChannel)
    }

    // === Credit operations ===

    suspend fun consumeCredit(appPackage: String? = null): Boolean = creditRepository.consume(appPackage = appPackage)

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_FOCUS_MONITOR = "focusflow_monitor"
        const val CHANNEL_FOCUS_WARNING = "focusflow_warning"

        lateinit var instance: MyPlanApp
            private set
    }
}
