package com.example.myplan.data

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.notification.AlarmDiagnostics
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AlarmDiagnosticsTest {
    private lateinit var app: Application
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("alarm_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun eventHistoryRemainsBoundedAndKeepsLatestDelivery() {
        repeat(200) { AlarmDiagnostics.record(app, "focus_denied", planId = it.toLong()) }
        val entries = JSONArray(app.getSharedPreferences("alarm_diagnostics", Context.MODE_PRIVATE).getString("events", "[]"))
        assertEquals(160, entries.length())
        assertEquals(199, entries.getJSONObject(159).getLong("plan"))
    }
    @Test fun reportDoesNotIncludePlanTextOrPrivateKeys() {
        SettingsManager.getPrefs(app).edit().putString("api_key", "private-key-value").putString("ringtone", "content://private-user-file").commit()
        AlarmDiagnostics.record(app, "alarm_received", planId = 12, reminderId = 13, scheduledAt = 100)
        val report = AlarmDiagnostics.report(app)
        assertTrue(report.contains("alarm_received")); assertTrue(report.contains("plan=12"))
        assertFalse(report.contains("private-key-value")); assertFalse(report.contains("private-user-file"))
    }
    @Test fun reportKeepsTheVolumeAtDeliveryEvenWhenItChangesBeforeExport() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        AlarmDiagnostics.record(app, "alarm_service_received")
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        val report = AlarmDiagnostics.report(app)
        assertTrue(report.contains("闹钟音量：5/"))
        assertTrue(report.contains("alarmVolume=2"))
    }
}
