package com.example.myplan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.notification.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MyPlanApp : Application() {

    // Focus: global unlock credits pool (reactive)
    private val _unlockCredits = MutableStateFlow(0)
    val unlockCredits: StateFlow<Int> = _unlockCredits.asStateFlow()

    // Focus: prevent same-day double credit for plans & tasks
    val planCreditsAwardedToday = mutableSetOf<Long>()
    val taskCreditsAwardedToday = mutableSetOf<Long>()

    // Focus: lazy DB singleton
    val focusDb: FocusDatabase by lazy { FocusDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Existing notification channel
        NotificationHelper.createChannel(this)

        // Focus notification channels
        createFocusChannels()
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

    fun addCredits(count: Int) { _unlockCredits.value += count }

    fun consumeCredit(): Boolean {
        if (_unlockCredits.value > 0) { _unlockCredits.value -= 1; return true }
        return false
    }

    fun resetCredits() {
        _unlockCredits.value = 0
        planCreditsAwardedToday.clear()
        taskCreditsAwardedToday.clear()
    }

    companion object {
        const val CHANNEL_FOCUS_MONITOR = "focusflow_monitor"
        const val CHANNEL_FOCUS_WARNING = "focusflow_warning"

        lateinit var instance: MyPlanApp
            private set
    }
}
