package com.example.myplan.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.notification.NotificationHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AlarmAudioRegressionTest {
    @Test fun alarmModeStartsAudioPlaybackService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SettingsManager.setReminderMode(app, "alarm")
        SettingsManager.setAlarmRingtone(app, "default")
        NotificationHelper.showReminderNotification(app, 7, "闹铃回归", null)
        val service = shadowOf(app).nextStartedService
        assertNotNull("闹铃必须启动音频播放，不能只发出通知", service)
        assertEquals("com.example.myplan.notification.AlarmPlaybackService", service.component!!.className)
    }
}
