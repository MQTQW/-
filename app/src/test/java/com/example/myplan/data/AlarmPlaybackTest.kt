package com.example.myplan.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.notification.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AlarmPlaybackTest {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private lateinit var controller: ServiceController<AlarmPlaybackService>
    private val players = mutableListOf<MediaPlayer>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SettingsManager.getPrefs(app).edit().clear().commit()
        SettingsManager.setVibrationMode(app, "off")
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        manager = app.getSystemService(NotificationManager::class.java)
        // Keep the fake clip longer than the service timeout; short looping clips
        // can leave ShadowMediaPlayer dispatching zero-delay completion events.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(120_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    }
    @After fun cleanup() { controller.destroy(); ShadowMediaPlayer.resetStaticState() }
    private fun play(id: Long = 7): Int {
        AlarmPlaybackService.start(app, id, "到点提醒", null)
        val intent = shadowOf(app).nextStartedService
        val result = controller.get().onStartCommand(intent, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return result
    }
    private fun stopIntent(): Intent = shadowOf(shadowOf(controller.get()).lastForegroundNotification.actions.single().actionIntent).savedIntent

    @Test fun startsLoopingAlarmAudioEvenWhenPhoneRingerIsSilent() {
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        play()
        val player = players.single()
        assertTrue(player.isPlaying); assertTrue(player.isLooping)
        assertEquals(AudioAttributes.USAGE_ALARM, shadowOf(player).audioAttributes.usage)
        assertEquals(7, shadowOf(controller.get()).lastForegroundNotificationId)
    }
    @Test fun stopActionReleasesPlayerAndAudioFocus() {
        play(); controller.get().onStartCommand(stopIntent(), 0, 2)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertNotNull(shadowOf(app.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
    @Test fun automaticallyStopsWithoutRestartAfterOneMinute() {
        assertEquals(android.app.Service.START_NOT_STICKY, play())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun staleStopActionDoesNotSilenceNewAlarm() {
        play(7); val oldStop = stopIntent(); play(8)
        controller.get().onStartCommand(oldStop, 0, 3)
        assertTrue(players.last().isPlaying)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun inaccessibleCustomAndDefaultSoundsFallBackToBundledAudio() {
        val uri = "content://unavailable/audio/1"
        SettingsManager.setAlarmRingtone(app, uri)
        AlarmSoundPlayer.sources(app, uri).dropLast(1).forEach {
            ShadowMediaPlayer.addException(DataSource.toDataSource(app, it), IOException("unavailable"))
        }
        play()
        assertTrue(players.last().isPlaying)
        assertEquals("android.resource", shadowOf(players.last()).sourceUri.scheme)
    }
    @Test fun explicitSilentChoiceNeverStartsAudioService() {
        SettingsManager.setAlarmRingtone(app, "silent")
        NotificationHelper.showReminderNotification(app, 7, "静音", null)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(players.isEmpty())
        assertNull(manager.getNotificationChannel(NotificationHelper.createChannel(app)).sound)
    }
    @Test fun notificationModeKeepsNormalNotificationDelivery() {
        SettingsManager.setReminderMode(app, "ring")
        NotificationHelper.showReminderNotification(app, 7, "通知模式", null)
        assertNull(shadowOf(app).nextStartedService)
        assertNotNull(shadowOf(manager).getNotification(7))
    }
    @Test fun audioFocusFailureEndsServiceInsteadOfPretendingToRing() {
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        play()
        assertTrue(players.isEmpty()); assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
    @Test fun dndAllowingAlarmsUsesIndependentAudioWithoutChangingDnd() {
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        manager.notificationPolicy = NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0)
        shadowOf(manager).setConsolidatedNotificationPolicy(NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0))
        play()
        assertTrue(players.single().isPlaying)
        assertNull(AlarmAudioStatus.warning(app))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, manager.currentInterruptionFilter)
    }
    @Test fun authorizedDndExceptionUsesAudibleChannelWithoutDoublePlayback() {
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        manager.notificationPolicy = NotificationManager.Policy(0, 0, 0)
        shadowOf(manager).setConsolidatedNotificationPolicy(NotificationManager.Policy(0, 0, 0))
        play()
        assertTrue(players.isEmpty())
        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertTrue(manager.getNotificationChannel(notification.channelId).canBypassDnd())
        assertNotEquals(0, notification.flags and Notification.FLAG_INSISTENT)
    }
    @Test fun fullSilenceAndZeroAlarmVolumeAreReportedWithoutOverridingSystem() {
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertTrue(AlarmAudioStatus.warning(app).orEmpty(), AlarmAudioStatus.warning(app)!!.contains("完全静音"))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, manager.currentInterruptionFilter)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        assertTrue(AlarmAudioStatus.warning(app)!!.contains("音量为 0"))
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, manager.currentInterruptionFilter)
    }
}
