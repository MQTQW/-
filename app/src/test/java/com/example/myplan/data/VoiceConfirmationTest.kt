package com.example.myplan.data

import android.app.Application
import com.example.myplan.voice.DeepSeekConversation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class VoiceConfirmationTest {
    private fun response(time: String = "08:30") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", JSONObject()
            .put("plan_name", "学英语").put("reminder_times", JSONArray().put(time))
            .put("repeat_mode", "EVERY_DAY").put("is_done", true).put("reply", "创建成功")
            .toString())))).toString()

    @Test fun modelDoneFlagCannotBypassUserConfirmation() {
        val conversation = DeepSeekConversation()
        val result = conversation.parseResponse(response())!!
        assertFalse(result.isDone)
        assertNull(result.planData)
        assertTrue(result.reply.contains("08:30"))
        assertEquals("学英语", conversation.confirmPending("确认")!!.name)
        assertNull(conversation.confirmPending("确认"))
    }

    @Test fun negativeConfirmationNeverMatchesAffirmativeSubstring() {
        val conversation = DeepSeekConversation()
        conversation.parseResponse(response())
        assertNull(conversation.confirmPending("不对"))
        assertNull(conversation.confirmPending("确认"))
    }

    @Test fun malformedTimeNeverBecomesConfirmable() {
        val conversation = DeepSeekConversation()
        val result = conversation.parseResponse(response("27:99"))!!
        assertFalse(result.reply.contains("成功"))
        assertNull(conversation.confirmPending("确认"))
    }
}
