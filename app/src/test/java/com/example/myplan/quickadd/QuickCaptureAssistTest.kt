package com.example.myplan.quickadd

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.assistant.PlanDraftWorkflow
import com.example.myplan.assistant.api.AssistParseResult
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.resetAppDatabases
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class QuickCaptureAssistTest {
    private lateinit var context: Context
    private lateinit var repo: PlanRepository
    private lateinit var store: QuickCaptureStore

    @Before fun setup() {
        resetAppDatabases()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("quick_capture", Context.MODE_PRIVATE).edit().clear().commit()
        repo = PlanRepository(PlanDatabase.getInstance(context))
        store = QuickCaptureStore(context)
    }
    @After fun close() { resetAppDatabases() }

    @Test fun draftStoreRetainsFocusCategoryMultipleTimesAndAbsoluteDate() {
        val draft = QuickDraft("学习", "CUSTOM", "1,3,5", at = 1_900_000_000_000L, hour = 8, minute = 30,
            date = "2030-03-17", issue = "请确认", alternatives = listOf(10L, 20L), focusMinutes = 45,
            category = "学习", reminderTimes = listOf("08:30", "20:00"))
        val record = CaptureRecord("roundtrip", draft, "pending", 21L, 42L)
        store.put(record)
        assertEquals(record, QuickCaptureStore(context).get("roundtrip"))
    }

    @Test fun oldDraftRecordsRemainReadableWithoutNewFields() {
        val old = JSONObject().put("title", "取件").put("mode", "ONCE").put("days", "")
            .put("hour", 9).put("minute", 0).put("alternatives", JSONArray()).put("status", "pending")
            .put("planId", 0).put("updatedAt", 123L)
        context.getSharedPreferences("quick_capture", Context.MODE_PRIVATE).edit().putString("old", old.toString()).commit()
        val draft = store.get("old")!!.draft
        assertNull(draft.focusMinutes); assertNull(draft.category); assertTrue(draft.reminderTimes.isEmpty())
        assertEquals("取件", draft.title)
    }

    @Test fun editableDraftRequiresSaveAndPreservesOnceAndFocus() = runBlocking {
        val at = System.currentTimeMillis() + 3_600_000
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val draft = QuickDraft("读书", at = at, hour = time.hour, minute = time.minute,
            focusMinutes = 15, category = "学习")
        store.put(CaptureRecord("once", draft, "entry"))
        assertTrue(repo.getAllPlans().isEmpty())
        val id = QuickCapture.saveEditor(context, "once", draft)
        val saved = repo.getPlanById(id)!!
        assertEquals(at, saved.oneTimeAt); assertEquals("ONCE", saved.repeatMode)
        assertEquals(15, saved.suggestedFocusMinutes); assertEquals("学习", saved.category)
        assertEquals("saved", store.get("once")!!.status)
    }

    @Test fun editingRetainsMultipleRemindersAndDoesNotResetFocusOrCategory() = runBlocking {
        val draft = QuickDraft("学习", "CUSTOM", "1,3,5", focusMinutes = 45, category = "工作",
            reminderTimes = listOf("08:30", "20:00"))
        store.put(CaptureRecord("repeat", draft, "entry"))
        val id = QuickCapture.saveEditor(context, "repeat", draft)
        val firstReminders = repo.getRemindersByPlanId(id)
        assertEquals(2, firstReminders.size)
        // Older callers may omit the new fields; editing must not reset previously saved values.
        QuickCapture.saveEditor(context, "repeat", draft.copy(title = "改名", focusMinutes = null, category = null))
        val saved = repo.getPlanById(id)!!
        assertEquals(45, saved.suggestedFocusMinutes); assertEquals("工作", saved.category)
        assertEquals("1,3,5", saved.customDays)
        assertEquals(firstReminders.map { it.id }, repo.getRemindersByPlanId(id).map { it.id })
    }

    @Test fun defaultFocusIsTwentyFiveAndSavedNotificationListsEveryTime() = runBlocking {
        val draft = QuickDraft("喝水", "EVERY_DAY", reminderTimes = listOf("09:00", "17:00"))
        store.put(CaptureRecord("default", draft, "entry"))
        val id = QuickCapture.saveEditor(context, "default", draft)
        assertEquals(25, repo.getPlanById(id)!!.suggestedFocusMinutes)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == 2 }.notification
        val text = notification.extras.getCharSequence("android.text").toString()
        assertTrue(text.contains("09:00、17:00"))
    }

    @Test fun explicitSmartActionCanWorkOfflineAndQuestionNeverCreatesPlan() = runBlocking {
        var remoteCalls = 0
        val workflow = PlanDraftWorkflow { _, _ -> remoteCalls++; AssistParseResult(null, "想几点提醒？") }
        val now = ZonedDateTime.parse("2030-10-26T15:00:00+08:00[Asia/Shanghai]")
        val result = workflow.parse("明晚八点读书，专注二十五分钟", now, allowAi = false)
        assertFalse(result.usedAi); assertEquals(25, result.plan!!.focusMinutes)
        assertEquals(0, remoteCalls); assertTrue(repo.getAllPlans().isEmpty())
        val question = workflow.parse("读书", now, allowAi = false)
        assertNull(question.plan); assertNotNull(question.question)
        assertEquals(0, remoteCalls); assertTrue(repo.getAllPlans().isEmpty())
    }
}
