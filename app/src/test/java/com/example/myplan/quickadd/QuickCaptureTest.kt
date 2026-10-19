package com.example.myplan.quickadd

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.SettingsManager
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.resetAppDatabases
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class QuickCaptureTest {
    private lateinit var context: Context
    private lateinit var repo: PlanRepository
    @Before fun setup() {
        resetAppDatabases()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("quick_capture", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsManager.setQuickCaptureEnabled(context, true)
        repo = PlanRepository(PlanDatabase.getInstance(context))
        QuickCaptureNotifications.refreshEntry(context)
    }
    @After fun close() { resetAppDatabases() }
    private fun entry(): String = UUID.randomUUID().toString().also { QuickCaptureStore(context).put(CaptureRecord(it, QuickDraft(""), "entry")) }
    @Test fun explicitRemoteTextCreatesExactlyOnePlanAcrossRetries() = runBlocking {
        val token = entry()
        QuickCapture.receive(context, token, "明天晚上六点取快递")
        QuickCapture.receive(context, token, "明天晚上六点取快递")
        assertEquals(1, repo.getAllPlans().size)
        assertEquals("取快递", repo.getByCreationToken(token)!!.name)
        assertEquals("ONCE", repo.getByCreationToken(token)!!.repeatMode)
    }
    @Test fun missingTimePersistsWithoutCreatingThenChoiceSavesIt() = runBlocking {
        val token = entry(); QuickCapture.receive(context, token, "取快递")
        assertTrue(repo.getAllPlans().isEmpty())
        val record = QuickCaptureStore(context).get(token)!!
        val offered = QuickCaptureNotifications.timeChoices(record.draft, Instant.ofEpochMilli(record.updatedAt).atZone(ZoneId.systemDefault()))
        assertTrue(offered.isNotEmpty())
        QuickCapture.choose(context, token, offered.first().second)
        assertEquals(offered.first().second, repo.getByCreationToken(token)!!.oneTimeAt)
    }
    @Test fun explicitDateIsPreservedByTimeChoices() = runBlocking {
        val token = entry(); QuickCapture.receive(context, token, "后天取快递")
        val record = QuickCaptureStore(context).get(token)!!
        val choices = QuickCaptureNotifications.timeChoices(record.draft)
        assertTrue(choices.all { Instant.ofEpochMilli(it.second).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now().plusDays(2) })
    }
    @Test fun undoCancelsAndOldInputCannotRecreate() = runBlocking {
        val token = entry(); QuickCapture.receive(context, token, "十分钟后喝水")
        assertNotNull(repo.getByCreationToken(token))
        QuickCapture.undo(context, token)
        QuickCapture.receive(context, token, "十分钟后喝水")
        assertNull(repo.getByCreationToken(token)); assertTrue(repo.getAllPlans().isEmpty())
    }
    @Test fun pastTimeNeverCreatesAnAlarm() = runBlocking {
        val token = entry(); QuickCapture.receive(context, token, "2020-01-01 09:00取件")
        assertTrue(repo.getAllPlans().isEmpty()); assertEquals("pending", QuickCaptureStore(context).get(token)!!.status)
        assertTrue(QuickCaptureNotifications.timeChoices(QuickCaptureStore(context).get(token)!!.draft).isEmpty())
    }
    @Test fun disabledQuickCaptureIgnoresOldReply() = runBlocking {
        val token = entry(); SettingsManager.setQuickCaptureEnabled(context, false)
        QuickCapture.receive(context, token, "明天18:00取件")
        assertTrue(repo.getAllPlans().isEmpty())
    }
    @Test fun entryUsesSystemRemoteInput() {
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 1 }.notification
        assertEquals("text", notification.actions.single().remoteInputs.single().resultKey)
    }
}
