package com.example.myplan.assistant.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No provider keys, prompts, plan contents or access codes are logged. */
class AssistService(
    private val config: () -> AssistConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) {
    suspend fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): AssistParseResult {
        requireInput(AssistJson.validInput(text, 1000))
        val response = request(payload("parse", text.trim(), now))
        return AssistJson.parse(response, now)
    }

    suspend fun split(title: String, description: String = ""): List<AssistStepSuggestion> {
        requireInput(AssistJson.validInput(title, 80) && AssistJson.validInput(description, 1000, allowEmpty = true))
        val body = payload("split", title.trim(), ZonedDateTime.now()).put("context", JSONObject()
            .put("title", title.trim()).put("description", description.trim()))
        return AssistJson.steps(request(body))
    }

    private fun payload(operation: String, text: String, now: ZonedDateTime) = JSONObject()
        .put("operation", operation).put("text", text)
        .put("now", now.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", now.zone.id)

    private fun requireInput(valid: Boolean) {
        if (!valid) throw AssistException(AssistError.INVALID_INPUT, "请缩短输入内容，并填写具体的计划事项。")
    }

    private suspend fun request(body: JSONObject): String {
        val configuration = config()
        val request = Request.Builder().url(configuration.validatedUrl())
            .header("Authorization", "Bearer ${configuration.accessToken}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(if (e is InterruptedIOException)
                        AssistException(AssistError.TIMEOUT, "AI 响应超时，请稍后重试。")
                    else AssistException(AssistError.NETWORK, "暂时连接不到 AI 服务，请检查网络和服务地址。"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            if (!response.isSuccessful) throw httpError(response.code)
                            val responseBody = response.body ?: throw invalidResponse()
                            if (responseBody.contentLength() > MAX_RESPONSE) throw invalidResponse()
                            val source = responseBody.source()
                            source.request(MAX_RESPONSE + 1)
                            if (source.buffer.size > MAX_RESPONSE) throw invalidResponse()
                            continuation.resume(source.readUtf8())
                        } catch (error: Exception) {
                            if (!continuation.isActive) return
                            continuation.resumeWithException(when (error) {
                                is AssistException -> error
                                is InterruptedIOException -> AssistException(AssistError.TIMEOUT, "AI 响应超时，请稍后重试。")
                                is IOException -> AssistException(AssistError.NETWORK, "AI 服务连接中断，请稍后重试。")
                                else -> invalidResponse()
                            })
                        }
                    }
                }
            })
        }
    }

    private fun invalidResponse() = AssistException(AssistError.INVALID_RESPONSE, "AI 返回的内容暂时无法使用，请重试或手动编辑。")
    private fun httpError(code: Int) = when (code) {
        401, 403 -> AssistException(AssistError.UNAUTHORIZED, "AI 服务访问码无效，请在设置中检查。")
        400, 413 -> AssistException(AssistError.INVALID_INPUT, "这段内容暂时无法处理，请缩短内容或手动编辑。")
        429 -> AssistException(AssistError.RATE_LIMITED, "AI 请求较多，请稍后再试。")
        504 -> AssistException(AssistError.TIMEOUT, "AI 响应超时，请稍后重试。")
        else -> AssistException(AssistError.SERVICE_UNAVAILABLE, "AI 服务暂时不可用，请稍后重试或手动编辑。")
    }

    companion object { private const val MAX_RESPONSE = 32L * 1024 }
}
