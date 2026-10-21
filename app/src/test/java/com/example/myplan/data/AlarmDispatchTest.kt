package com.example.myplan.data

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.notification.AlarmReceiver
import com.example.myplan.notification.AlarmScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AlarmDispatchTest {
    private lateinit var app: Application
    private lateinit var alarms: AlarmManager
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SettingsManager.getPrefs(app).edit().clear().commit()
        alarms = app.getSystemService(AlarmManager::class.java)
    }

    @Test fun alarmClockStartsForegroundServiceWithoutWaitingForABroadcastReceiver() {
        AlarmScheduler(app).scheduleAlarm(1, 2, System.currentTimeMillis() + 60_000, "提醒", null,
            scheduleVersion = 7)
        val intent = shadowOf(alarms).scheduledAlarms.single().operation
        assertTrue("闹铃应由系统直接启动前台服务，避免等待后台广播处理", shadowOf(intent).isForegroundService)
        assertEquals(7L, shadowOf(intent).savedIntent.getLongExtra("schedule_version", -1))
    }

    @Test fun oneMinuteTestUsesTheSameForegroundDeliveryAsRealAlarms() {
        AlarmScheduler(app).scheduleTestAlarm()
        val operation = shadowOf(alarms).scheduledAlarms.single().operation
        assertTrue(shadowOf(operation).isForegroundService)
    }

    @Test fun migratingAnExistingAlarmDoesNotLeaveTheLegacyBroadcastArmed() {
        val legacy = PendingIntent.getBroadcast(app, 2, Intent(app, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 60_000, null), legacy)
        AlarmScheduler(app).scheduleAlarm(1, 2, System.currentTimeMillis() + 120_000, "修改后的提醒", null)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertTrue(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).isForegroundService)
        AlarmScheduler(app).cancelAlarm(2)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun notificationOnlyModeKeepsBroadcastDelivery() {
        SettingsManager.setReminderMode(app, "ring")
        AlarmScheduler(app).scheduleAlarm(1, 2, System.currentTimeMillis() + 60_000, "普通通知", null)
        assertTrue(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).isBroadcast)
    }
}
