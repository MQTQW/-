package com.example.myplan.assistant.api

import android.content.Context
import androidx.core.content.edit
import com.example.myplan.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AssistConfig(val endpoint: String = "", val accessToken: String = "") {
    val isConfigured: Boolean get() = endpoint.isNotBlank() && accessToken.isNotBlank()
    override fun toString(): String = "AssistConfig(configured=$isConfigured)"

    /** Endpoint is the complete /v1/assist URL, never an upstream provider API URL. */
    fun validatedUrl(debug: Boolean = BuildConfig.DEBUG): HttpUrl {
        if (!isConfigured) throw AssistException(AssistError.NOT_CONFIGURED, "请先在设置中配置 AI 服务地址和访问码。")
        val url = endpoint.toHttpUrlOrNull()
        val loopback = url?.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            (url.scheme != "https" && !(debug && loopback)) || !url.encodedPath.endsWith("/v1/assist") ||
            accessToken.length !in 32..256 || accessToken.any { it.code !in 33..126 }) {
            throw AssistException(AssistError.INVALID_CONFIGURATION, "请检查 AI 服务地址和访问码；服务地址需使用 HTTPS 并以 /v1/assist 结尾。")
        }
        return url
    }
}

/** This preference file is excluded from Android cloud backup and device transfer. */
class AssistConfiguration(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ai_assist_settings", Context.MODE_PRIVATE)

    fun load(): AssistConfig = AssistConfig(
        preferences.getString("endpoint", "").orEmpty(),
        preferences.getString("access_token", "").orEmpty()
    )

    fun save(endpoint: String, accessToken: String) {
        val config = AssistConfig(endpoint.trim(), accessToken.trim())
        config.validatedUrl()
        preferences.edit { putString("endpoint", config.endpoint); putString("access_token", config.accessToken) }
    }

    fun clear() { preferences.edit { clear() } }
}
