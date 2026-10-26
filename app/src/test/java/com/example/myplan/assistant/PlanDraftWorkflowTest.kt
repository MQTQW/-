package com.example.myplan.assistant

import android.app.Application
import com.example.myplan.assistant.api.*
import com.example.myplan.voice.PlanVoiceData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PlanDraftWorkflowTest {
    private val now = ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun clearOnceAndFocusStayLocalAndUseExactFutureDate() = runBlocking {
        val workflow = PlanDraftWorkflow { _, _ -> error("Clear input must not be uploaded") }
        val result = workflow.parse("明晚八点提醒我读书，先专注二十五分钟", now)
        val plan = result.plan!!
        assertFalse(result.usedAi)
        assertEquals("ONCE", plan.repeatMode)
        assertEquals("读书", plan.name)
        assertEquals(25, plan.focusMinutes)
        assertEquals(listOf("20:00"), plan.reminderTimes)
        assertEquals(now.toLocalDate().plusDays(1).atTime(20, 0).atZone(now.zone).toInstant().toEpochMilli(), plan.oneTimeAt)
    }

    @Test fun ambiguousOrMissingTimeNeedsAnAnswerOffline() = runBlocking {
        val workflow = PlanDraftWorkflow { _, _ -> error("Offline must not call AI") }
        assertNull(workflow.parse("明天八点读书", now, allowAi = false).plan)
        val result = workflow.parse("明天读书", now, allowAi = false)
        assertNull(result.plan)
        assertTrue(result.question!!.contains("时间"))
    }

    @Test fun unsupportedDurationIsNotSilentlyDropped() = runBlocking {
        val result = PlanDraftWorkflow { _, _ -> error("Invalid local duration should ask first") }
            .parse("明晚八点读书，专注二十分钟", now)
        assertNull(result.plan)
        assertTrue(result.question!!.contains("15"))
    }

    @Test fun unsupportedRecurrenceCannotBeGuessedByRemoteAi() = runBlocking {
        val workflow = PlanDraftWorkflow { _, _ -> error("Unsupported recurrence must not be guessed by a model") }
        listOf("每月1号缴费", "隔周运动", "每两周读书", "每年生日提醒").forEach { text ->
            assertNull(workflow.parse(text, now).plan)
            assertNotNull(workflow.parse(text, now).question)
        }
    }

    @Test fun complexRecurringDraftKeepsAllTimesAndCustomDays() = runBlocking {
        val draft = AssistDraft("运动", "健康", AssistRepeatMode.CUSTOM, null,
            listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), listOf(1, 3, 5), 15)
        val result = PlanDraftWorkflow { _, sentNow ->
            assertEquals(now, sentNow)
            AssistParseResult(draft, null)
        }.parse("逢周一三五早八点和晚八点运动", now)
        assertTrue(result.usedAi)
        assertEquals(setOf(1, 3, 5), result.plan!!.customDays)
        assertEquals(listOf("08:00", "20:00"), result.plan.reminderTimes)
        assertNull(result.plan.oneTimeAt)
    }

    @Test fun clarificationCannotBecomeAConfirmablePlan() = runBlocking {
        val result = PlanDraftWorkflow { _, _ -> AssistParseResult(null, "哪天提醒？") }
            .parse("下班后运动", now)
        assertNull(result.plan)
        assertEquals("哪天提醒？", result.question)
    }

    @Test fun confirmationRevalidatesTimeRatherThanTrustingOldPreview() {
        val plan = PlanVoiceData(name = "喝水", reminderTimes = listOf("10:01"), repeatMode = "ONCE",
            oneTimeAt = now.plusMinutes(1).toInstant().toEpochMilli())
        PlanDraftWorkflow.validate(plan, now)
        assertThrows(IllegalArgumentException::class.java) { PlanDraftWorkflow.validate(plan, now.plusMinutes(2)) }
    }

    @Test fun draftRestorePreservesDateFocusAndIdempotencyToken() {
        val original = PlanVoiceData("读书", listOf("20:00"), "ONCE", category = "学习",
            oneTimeAt = now.plusDays(1).withHour(20).toInstant().toEpochMilli(), focusMinutes = 45, creationToken = "same-confirmation")
        assertEquals(original, PlanDraftCodec.decode(PlanDraftCodec.encode(original)))
    }
}
