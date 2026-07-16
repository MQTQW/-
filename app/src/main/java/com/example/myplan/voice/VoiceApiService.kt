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
 * DeepSeek Function Calling API 封装。
 * V1.5 在线增强：当规则引擎置信度低时，调用 DeepSeek 做结构化槽位提取。
 *
 * 使用 Function Calling 模式，DeepSeek 直接返回 JSON 格式的槽位数据，
 * 无需手动解析自然语言输出。
 *
 * 参考：iris 的 GeminiClient.kt + Half_duplex 项目的 DeepSeek 集成
 */
object VoiceApiService {

    private const val TAG = "VoiceApi"
    private const val BASE_URL = "https://api.deepseek.com/v1/chat/completions"
    private var apiKey: String? = null

    /** 初始化 API Key（从 BuildConfig 或加密存储获取） */
    fun init(key: String) {
        apiKey = key
    }

    /** 检查 API 是否可用 */
    fun isAvailable(): Boolean = apiKey != null

    /**
     * 使用 DeepSeek Function Calling 提取槽位。
     * 超时 3 秒，失败返回 null → 调用方降级为规则引擎。
     */
    suspend fun extractSlots(userText: String): SlotResult? = withContext(Dispatchers.IO) {
        val key = apiKey ?: return@withContext null

        val result = withTimeoutOrNull(3000L) {
            try {
                val requestJson = buildRequest(userText)
                val responseJson = callApi(requestJson, key)
                if (responseJson != null) parseFunctionCall(responseJson) else null
            } catch (e: Exception) {
                Log.e(TAG, "API call failed", e)
                null
            }
        }

        if (result == null) {
            Log.w(TAG, "API timed out or failed, fallback to rule engine")
        }
        result
    }

    private fun buildRequest(userText: String): JSONObject {
        return JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.1)
            put("max_tokens", 256)

            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", """
你是 MY PLAN 的语音助手。从用户输入中提取计划创建信息，输出结构化 JSON。

规则：
- plan_name: 计划名称（去除"帮我""提醒我""制定"等噪声词后的核心内容）
- reminder_times: 所有提醒时间，24小时制 HH:MM 格式数组
- repeat_mode: "EVERY_DAY" / "WEEKDAYS" / "CUSTOM"
- custom_days: 仅当 repeat_mode=CUSTOM 时，返回星期数字列表 (1=周一 ~ 7=周日)，否则为 null
- category: "健康"/"学习"/"工作"/"日常"/"紧急"/"其他"
- is_cancel: 用户是否表示取消操作
- is_confirm: 用户是否表示确认创建

无法确定时用 null，不要编造。
""".trimIndent())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })

            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", "extract_plan_slots")
                        put("description", "从用户语音输入中提取计划创建的槽位信息")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("plan_name", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "计划名称")
                                })
                                put("reminder_times", JSONObject().apply {
                                    put("type", "array")
                                    put("items", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "时间，HH:MM格式")
                                    })
                                    put("description", "提醒时间列表")
                                })
                                put("repeat_mode", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray(listOf("EVERY_DAY", "WEEKDAYS", "CUSTOM")))
                                    put("description", "重复模式")
                                })
                                put("custom_days", JSONObject().apply {
                                    put("type", "array")
                                    put("items", JSONObject().apply {
                                        put("type", "integer")
                                        put("minimum", 1)
                                        put("maximum", 7)
                                    })
                                    put("description", "自定义星期，1=周一~7=周日")
                                })
                                put("category", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray(listOf("健康", "学习", "工作", "日常", "紧急", "其他")))
                                    put("description", "分类")
                                })
                                put("is_cancel", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "用户是否取消")
                                })
                                put("is_confirm", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "用户是否确认创建")
                                })
                            })
                            put("required", JSONArray(listOf("plan_name", "reminder_times", "repeat_mode")))
                        })
                    })
                })
            })

            put("tool_choice", "auto")
        }
    }

    private fun callApi(requestJson: JSONObject, key: String): JSONObject? {
        val url = URL(BASE_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key") // Key in header, not URL param
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write(requestJson.toString().toByteArray())
            }

            if (conn.responseCode != 200) {
                Log.e(TAG, "API error: ${conn.responseCode}")
                return null
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            return JSONObject(responseText)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFunctionCall(response: JSONObject): SlotResult? {
        try {
            val choices = response.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")

            // Check for tool_calls
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val function = toolCalls.getJSONObject(0).getJSONObject("function")
                val args = JSONObject(function.getString("arguments"))

                return SlotResult(
                    planName = args.optString("plan_name", null),
                    reminderTimes = args.optJSONArray("reminder_times")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    repeatMode = args.optString("repeat_mode", null),
                    customDays = args.optJSONArray("custom_days")?.let { arr ->
                        (0 until arr.length()).map { arr.getInt(it) }.toSet()
                    },
                    category = args.optString("category", null),
                    isCancel = args.optBoolean("is_cancel", false),
                    isConfirm = args.optBoolean("is_confirm", false),
                    confidence = 0.95f
                )
            }

            // Fallback: parse from content if no tool_call
            val content = message.optString("content", "")
            if (content.isNotBlank()) {
                return SlotFiller.parse(content) // Use rule engine as fallback parser
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse API response", e)
            return null
        }
    }
}
