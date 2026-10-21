package com.example.myplan.data

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AlarmDirectDeliveryTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var db: PlanDatabase
    private lateinit var repo: PlanRepository
    private lateinit var service: ServiceController<AlarmPlaybackService>
    private val players = mutableListOf<MediaPlayer>()
    private var destroyed = false

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        SettingsManager.getPrefs(app).edit().clear().commit()
        SettingsManager.setVibrationMode(app, "off")
        resetAppDatabases()
        db = Room.inMemoryDatabaseBuilder(app, PlanDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        PlanDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        repo = PlanRepository(db)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(120_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        service = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    }
    @After fun cleanup() {
        if (!destroyed) service.destroy()
        db.close(); resetAppDatabases()
        ShadowMediaPlayer.resetStaticState(); Dispatchers.resetMain()
    }

    private fun plan(once: Boolean = false): Intent = runBlocking {
        val (id, _) = repo.savePlanWithReminders(PlanEntity(name = "有效提醒",
            repeatMode = if (once) "ONCE" else "EVERY_DAY",
            oneTimeAt = if (once) System.currentTimeMillis() + 60_000 else null),
            listOf(ReminderEntity(planId = 0, hour = 9, minute = 0)))
        val reminder = repo.getRemindersByPlanId(id).single()
        AlarmPlaybackService.scheduledIntent(app, reminder.id).putExtra("plan_id", id)
            .putExtra("reminder_id", reminder.id).putExtra("schedule_version", reminder.scheduleVersion)
            .putExtra("scheduled_at", System.currentTimeMillis())
    }
    private fun drain() {
        dispatcher.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun foregroundAndWakeLockAreEstablishedBeforeDatabaseValidation() {
        val intent = plan()
        service.get().onStartCommand(intent, 0, 1)
        assertEquals("正在确认提醒", shadowOf(service.get()).lastForegroundNotification.extras.getCharSequence("android.title"))
        assertTrue(players.isEmpty())
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        drain()
        assertTrue(players.single().isPlaying)
        assertNull("到点播放不能依赖启动界面", shadowOf(app).nextStartedActivity)
    }
    @Test fun modifiedVersionIsIgnoredWithoutPlayingOrLeavingPreparationRunning() {
        val intent = plan().putExtra("schedule_version", -1L)
        service.get().onStartCommand(intent, 0, 1); drain()
        assertTrue(players.isEmpty())
        assertTrue(shadowOf(service.get()).isForegroundStopped)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }
    @Test fun pausedPlanCannotRingThroughADelayedServiceIntent() = runBlocking {
        val intent = plan(); val id = intent.getLongExtra("plan_id", 0)
        repo.updatePlan(repo.getPlanById(id)!!.copy(isEnabled = false))
        service.get().onStartCommand(intent, 0, 1); drain()
        assertTrue(players.isEmpty())
    }
    @Test fun duplicateOneTimeDeliveryDoesNotRestartThePlayer() {
        val intent = plan(once = true)
        service.get().onStartCommand(intent, 0, 1); drain()
        service.get().onStartCommand(intent, 0, 2); drain()
        assertEquals(1, players.size); assertTrue(players.single().isPlaying)
    }
    @Test fun anIgnoredIncomingEventDoesNotStopAnAlarmAlreadyPlaying() {
        val intent = plan()
        service.get().onStartCommand(intent, 0, 1); drain()
        service.get().onStartCommand(Intent(intent).putExtra("schedule_version", -1L), 0, 2); drain()
        assertTrue(players.single().isPlaying)
        assertFalse(shadowOf(service.get()).isStoppedBySelf)
    }
    @Test fun minuteTestUsesTheSamePlayerWithoutOpeningAnActivity() {
        service.get().onStartCommand(AlarmPlaybackService.scheduledIntent(app, 0, test = true), 0, 1); drain()
        assertTrue(players.single().isPlaying)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun anExplicitSilentChoiceStillSuppressesAudioOnADirectDelivery() {
        SettingsManager.setAlarmRingtone(app, "silent")
        service.get().onStartCommand(plan(), 0, 1); drain()
        assertTrue(players.isEmpty()); assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }
    @Test fun destroyingTheServiceCancelsAQueuedDeliveryAndReleasesItsWakeLock() {
        service.get().onStartCommand(plan(), 0, 1)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        service.destroy(); destroyed = true
        drain()
        assertTrue(players.isEmpty()); assertFalse(lock.isHeld)
    }
}
