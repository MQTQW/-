package com.example.myplan.travel.live

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TravelLiveGateway {
    suspend fun session(): TravelConnection
    suspend fun research(destination: String): String
    suspend fun plan(researchId: String, selected: Map<String, String>, form: TravelLiveForm): String
    suspend fun job(id: String): TravelLiveJob
    suspend fun image(path: String): ByteArray?
}

/** All request paths are closed over this local origin; redirects and credential logging are disabled. */
class TravelLiveApi(private val config: TravelLiveConfig, client: OkHttpClient = OkHttpClient()) : TravelLiveGateway {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    @Volatile private var cookie: String? = null
    @Volatile private var csrf: String? = null

    override suspend fun session(): TravelConnection {
        val json = json("session")
        csrf = json.optString("csrfToken").takeIf { it.length in 16..256 }
            ?: throw TravelLiveException("INVALID_RESPONSE", "服务未建立有效会话，请重新连接。")
        val cap = json.getJSONObject("capabilities"); val connection = json.getJSONObject("connection")
        return TravelConnection(cap.optBoolean("research"), cap.optBoolean("ai"), cap.optBoolean("map"), connection.optBoolean("loggedIn"),
            if (connection.optBoolean("loggedIn")) "已连接电脑旅行服务" else "电脑端小红书尚未登录，请先扫码登录。")
    }
    override suspend fun research(destination: String): String {
        if (csrf == null) session()
        return json("research", JSONObject().put("destination", destination).put("count", 3)).getString("jobId").also(::validJobId)
    }
    override suspend fun plan(researchId: String, selected: Map<String, String>, form: TravelLiveForm): String {
        if (csrf == null) session()
        return json("plan", JSONObject().put("researchId", researchId).put("selected", JSONArray().apply {
            selected.forEach { (id, priority) -> put(JSONObject().put("id", id).put("priority", priority)) }
        }).put("form", form.json())).getString("jobId").also(::validJobId)
    }
    override suspend fun job(id: String): TravelLiveJob {
        validJobId(id)
        val json = json("jobs/$id")
        require(json.getString("id") == id)
        val status = json.getString("status"); require(status in setOf("running", "done", "failed"))
        return TravelLiveJob(id, json.getString("kind"), status, json.optString("progress").take(500), json.optJSONObject("result"), json.optJSONObject("error")?.optString("code"))
    }
    override suspend fun image(path: String): ByteArray? {
        val safe = TravelLiveJson.imagePath(path) ?: return null
        return try { request("images/" + safe.substringAfterLast('/'), maximum = 5L * 1024 * 1024, image = true) }
        catch (error: TravelLiveException) { if (error.code in setOf("IMAGE_NOT_FOUND", "IMAGE_UNAVAILABLE", "NETWORK_ERROR")) null else throw error }
    }
    private suspend fun json(path: String, body: JSONObject? = null): JSONObject = try {
        JSONObject(request(path, body).toString(Charsets.UTF_8))
    } catch (e: CancellationException) { throw e }
    catch (e: TravelLiveException) { throw e }
    catch (_: Exception) { throw TravelLiveException("INVALID_RESPONSE", "服务返回的内容无法读取，请重新连接后重试。") }

    private suspend fun request(route: String, body: JSONObject? = null, maximum: Long = 2L * 1024 * 1024, image: Boolean = false): ByteArray {
        require(Regex("^(session|research|plan|jobs/[a-f0-9-]{36}|images/[A-Za-z0-9_-]{10,100})$").matches(route))
        val base = config.validatedUrl()
        val url = base.newBuilder().addPathSegments("api/native/$route").apply { if (route == "session") addQueryParameter("refresh", "1") }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.token}").header("Accept", if (image) "image/*" else "application/json")
            .apply { cookie?.let { header("Cookie", it) }; if (body != null) { header("X-CSRF-Token", csrf.orEmpty()); post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) } }.build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(TravelLiveException("NETWORK_ERROR", "连接不到电脑旅行服务，请确认 USB 调试转发和服务已启动。"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!cont.isActive) return
                        try {
                            val responseBody = response.body ?: throw error("INVALID_RESPONSE")
                            val limit = if (response.isSuccessful) maximum else 16L * 1024
                            if (responseBody.contentLength() > limit) throw error("INVALID_RESPONSE")
                            val source = responseBody.source(); source.request(limit + 1)
                            if (source.buffer.size > limit) throw error("INVALID_RESPONSE")
                            val bytes = source.readByteArray()
                            if (!response.isSuccessful) {
                                val code = try { JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("error")?.optString("code").orEmpty() } catch (_: Exception) { "REQUEST_FAILED" }
                                throw error(code)
                            }
                            if (image && response.header("Content-Type")?.substringBefore(';') !in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) throw error("INVALID_RESPONSE")
                            response.headers.values("Set-Cookie").mapNotNull { Cookie.parse(url, it) }.firstOrNull { it.name == "myplan_native_session" && it.path == "/api/native" && it.hostOnly && it.domain == url.host }
                                ?.let { cookie = "${it.name}=${it.value}" }
                            cont.resume(bytes)
                        } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(if (e is TravelLiveException) e else error("INVALID_RESPONSE")) }
                    }
                }
            })
        }
    }
    private fun validJobId(id: String) { require(Regex("^[a-f0-9-]{36}$").matches(id)) }
    companion object {
        fun error(code: String): TravelLiveException = TravelLiveException(code, when (code) {
            "NATIVE_AUTH_REQUIRED", "NATIVE_AUTH_REJECTED", "NATIVE_UNAUTHORIZED", "UNAUTHORIZED" -> "连接码无效，请核对电脑端的连接码。"
            "SESSION_REQUIRED", "CSRF_REJECTED" -> "连接已过期，请重新检测服务；旧资料可能需要重新检索，已选内容仍保留。"
            "RESEARCH_EXPIRED", "JOB_NOT_FOUND" -> "这份资料或任务已过期，请重新检索；当前清单仍保留。"
            "ACTIVE_JOB_CONFLICT" -> "电脑仍在处理上一次任务，请继续查看原任务或等待完成。"
            "SERVICE_BUSY" -> "旅行服务正在处理其他任务，请稍后重试。"
            "AI_NOT_CONFIGURED" -> "电脑端尚未配置 AI 服务，请完成服务端配置后重试。"
            "IMAGE_NOT_FOUND", "IMAGE_UNAVAILABLE" -> "攻略配图暂不可用，文字和来源仍可查看。"
            "NOT_FOUND" -> "电脑端尚未开启手机接入，请使用最新版旅行服务与手机连接工具。"
            else -> "本次请求未能完成，请检查电脑服务后重试。"
        })
    }
}
