package com.example.myplan.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 对话引擎 —— 用 LLM 管理整个语音对话流程。
 * 替代硬编码状态机，让 LLM 自己决定追问什么、什么时候确认。
 */
class DeepSeekConversation {

    private val TAG = "DeepSeekConv"
    private val API_URL = "https://api.deepseek.com/v1/chat/completions"
    private var apiKey: String? = null

    fun init(key: String) { apiKey = key }

    private val history = mutableListOf<Pair<String, String>>() // (role, content)
    private val slots = mutableMapOf<String, Any>()
    private var pendingConfirmation: PlanVoiceData? = null

    internal fun pendingPlan(): PlanVoiceData? = pendingConfirmation

    fun resetConversation() {
        history.clear()
        slots.clear()
        pendingConfirmation = null
    }

    data class TurnResult(
        val reply: String,
        val planData: PlanVoiceData?,
        val isDone: Boolean,
        val isCancel: Boolean
    )

    suspend fun processUserInput(userText: String): TurnResult? = withContext(Dispatchers.IO) {
        val confirmed = confirmPending(userText)
        if (confirmed != null) {
            return@withContext TurnResult("计划信息已确认。", confirmed, true, false)
        }
        val key = apiKey ?: return@withContext null

        // Retry once if empty response (flash model sometimes drifts)
        suspend fun attempt(): TurnResult? = withTimeoutOrNull(8000L) {
            try {
                val requestBody = buildRequest(userText)
                val response = callApi(requestBody, key) ?: return@withTimeoutOrNull null
                parseResponse(response)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek failed", e)
                null
            }
        }
        attempt() ?: run {
            Log.w(TAG, "First attempt failed, retrying after 500ms...")
            kotlinx.coroutines.delay(500)
            attempt()
        }
    }

    private fun buildRequest(userText: String): JSONObject {
        // Build slot status string
        val slotStatus = StringBuilder("已填槽位: ")
        if (slots.isEmpty()) slotStatus.append("无")
        else slots.forEach { (k, v) -> slotStatus.append("$k=$v, ") }

        history.add("user" to userText)

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        if (slots.isNotEmpty()) messages.put(JSONObject().apply {
            put("role", "system")
            put("content", slotStatus.toString())
        })
        for ((role, content) in history.takeLast(8)) {
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        return JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.3)
            put("max_tokens", 512)
            put("messages", messages)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }
    }

    private fun callApi(body: JSONObject, key: String): String? {
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                Log.e(TAG, "HTTP ${conn.responseCode}")
                return null
            }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    internal fun confirmPending(text: String): PlanVoiceData? {
        val pending = pendingConfirmation
        pendingConfirmation = null
        val answer = text.trim().trimEnd('。', '！', '!', '.', '，')
        return pending?.takeIf { answer in setOf("确认", "确认创建", "好的", "好", "可以", "是", "对", "没错") }
    }

    internal fun parseResponse(json: String): TurnResult? {
        Log.d(TAG, "Response: ${json.take(300)}")
        val root = JSONObject(json)
        val content = root.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message")
            .getString("content").trim()

        if (content.isEmpty()) {
            Log.w(TAG, "Empty content from DeepSeek")
            return null
        }

        val cleanJson = content
            .replace("```json", "").replace("```", "").trim()

        val r = try {
            JSONObject(cleanJson)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}, content=$cleanJson")
            return null
        }
        val reply = r.optString("reply", "好的少爷。")

        // Update slots from response
        r.optString("plan_name", "").takeIf { !r.isNull("plan_name") && it.isNotBlank() }?.let { slots["plan_name"] = it }
        val times = r.optJSONArray("reminder_times")
        if (times != null && times.length() > 0) {
            val list = (0 until times.length()).map { times.getString(it) }
            slots["reminder_times"] = list
        }
        r.optString("repeat_mode", "").takeIf { !r.isNull("repeat_mode") && it.isNotBlank() }?.let { slots["repeat_mode"] = it }
        val days = r.optJSONArray("custom_days")
        if (days != null && days.length() > 0) {
            slots["custom_days"] = (0 until days.length()).map { days.getInt(it) }.toSet()
        }
        r.optString("category", "").takeIf { it in setOf("日常", "工作", "学习", "健康", "紧急", "其他") }?.let { slots["category"] = it }

        val isCancel = r.optBoolean("is_cancel", false)

        history.add("assistant" to reply)

        // Build PlanVoiceData if done
        val planData =
            PlanVoiceData(
                name = slots["plan_name"] as? String ?: "",
                reminderTimes = (slots["reminder_times"] as? List<String>) ?: emptyList(),
                repeatMode = slots["repeat_mode"] as? String ?: "EVERY_DAY",
                customDays = (slots["custom_days"] as? Set<Int>) ?: emptySet(),
                category = slots["category"] as? String ?: "日常"
            )
        val valid = planData.name.isNotBlank() && planData.reminderTimes.isNotEmpty() &&
            planData.reminderTimes.all { Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) } &&
            planData.repeatMode in setOf("EVERY_DAY", "WEEKDAYS", "CUSTOM") &&
            (planData.repeatMode != "CUSTOM" || (planData.customDays.isNotEmpty() && planData.customDays.all { it in 1..7 }))
        if (valid && !isCancel) {
            pendingConfirmation = planData
            val repeat = when (planData.repeatMode) {
                "WEEKDAYS" -> "工作日"
                "CUSTOM" -> "每周" + planData.customDays.sorted().joinToString("、")
                else -> "每天"
            }
            return TurnResult("「${planData.name}」，$repeat ${planData.reminderTimes.joinToString("、")}提醒。确认创建吗？", null, false, false)
        }
        val safeReply = if (!isCancel && (r.optBoolean("is_done", false) ||
                listOf("创建成功", "已创建", "已保存").any { it in reply }))
            "计划信息还不完整，请补充计划名称和有效的提醒时间。" else reply
        return TurnResult(safeReply, null, false, isCancel)
    }

    private val systemPrompt = """
你是 MY PLAN 的语音助手，称呼用户为"少爷"。你正在通过语音对话帮少爷创建计划。

## 计划字段
- plan_name：计划名称
- reminder_times：提醒时间列表（HH:MM格式数组），如["08:00","20:00"]
- repeat_mode：EVERY_DAY | WEEKDAYS | CUSTOM
- custom_days：仅当 repeat_mode=CUSTOM，星期数组 1=周一~7=周日，否则 null
- category：日常/工作/学习/健康/紧急/其他，少爷没说就填"日常"

## 对话规则
- 每次只追问 1-2 个少爷还没说的信息
- 少爷一句话里可能带了多个字段——全部提取，不重复追问
- 字段不全时追问，字段全了复述确认
- "不用了"在提醒时间环节 = "不加更多时间了"，不是取消
- "取消"/"算了"在非提醒时间环节 = 取消整个流程

## 回复风格
- 自然口语，每次不超过 30 字
- 先确认少爷刚说的，再追问缺失的
- 字段全了这样确认："好的少爷——「学英语」，工作日08:00、20:00提醒你。确认创建吗？"

## 输出（严格 JSON，不要任何其他文字）
{
  "plan_name": null,
  "reminder_times": null,
  "repeat_mode": null,
  "custom_days": null,
  "category": null,
  "reply": "少爷想创建什么计划呢？",
  "is_done": false,
  "is_cancel": false
}
""".trimIndent()
}
