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

class MyPlanApp : Application() {

    // Focus: global unlock credits pool (reactive)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val creditRepository by lazy { CreditRepository(PlanDatabase.getInstance(this)) }
    val unlockCredits: StateFlow<Int> by lazy {
        creditRepository.balance.stateIn(appScope, SharingStarted.Eagerly, 0)
    }

    // Focus: lazy DB singleton
    val focusDb: FocusDatabase by lazy { FocusDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Existing notification channel
        NotificationHelper.createChannel(this)

        // Focus notification channels
        createFocusChannels()
        appScope.launch {
            try { DailyRollover.ensureToday(this@MyPlanApp) }
            catch (e: Exception) { android.util.Log.e("MyPlanApp", "Daily recovery failed", e) }
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

    companion object {
        const val CHANNEL_FOCUS_MONITOR = "focusflow_monitor"
        const val CHANNEL_FOCUS_WARNING = "focusflow_warning"

        lateinit var instance: MyPlanApp
            private set
    }
}
