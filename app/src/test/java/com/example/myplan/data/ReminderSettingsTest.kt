package com.example.myplan.data

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.NotificationHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ReminderSettingsTest {
    @Test fun alarmSoundIsManagedByDismissibleSystemNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsManager.setReminderMode(context, "alarm")
        NotificationHelper.showReminderNotification(context, 7, "闹钟测试", null)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = org.robolectric.Shadows.shadowOf(manager).getNotification(7)
        assertNotEquals(0, notification.flags and android.app.Notification.FLAG_INSISTENT)
        assertNotEquals(0, notification.flags and android.app.Notification.FLAG_AUTO_CANCEL)
    }

    @Test fun silentSelectionAndVibrationAreAppliedToChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsManager.setAlarmRingtone(context, "silent")
        SettingsManager.setVibrationMode(context, "off")
        val id = NotificationHelper.createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(id).sound)
        assertFalse(manager.getNotificationChannel(id).shouldVibrate())
        SettingsManager.setAlarmRingtone(context, "default")
        val newId = NotificationHelper.createChannel(context)
        assertNotEquals(id, newId)
        assertNotNull(manager.getNotificationChannel(newId).sound)
        assertEquals(newId, NotificationHelper.createChannel(context))
    }

    @Test fun customMondaySkipsWeekendAndEmptySelectionDoesNotInventDays() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = AlarmScheduler(context)
        val friday = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        val next = scheduler.computeNextFireTime(8, 30, "CUSTOM", setOf(1), friday.timeInMillis)!!
        val monday = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, monday.get(Calendar.DAY_OF_WEEK))
        assertEquals(19, monday.get(Calendar.DAY_OF_MONTH))
        assertEquals(30, monday.get(Calendar.MINUTE))
        assertNull(scheduler.computeNextFireTime(8, 30, "CUSTOM", emptySet(), friday.timeInMillis))
    }
}
