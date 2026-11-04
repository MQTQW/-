package com.example.myplan.travel.live

import android.content.Context
import androidx.core.content.edit
import com.example.myplan.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A local bridge access code, never a provider API key. */
data class TravelLiveConfig(val baseUrl: String = "http://127.0.0.1:8794", val token: String = "") {
    val isConfigured: Boolean get() = token.isNotBlank()
    override fun toString() = "TravelLiveConfig(configured=$isConfigured)"
    fun validatedUrl(debug: Boolean = BuildConfig.DEBUG): HttpUrl {
        val url = baseUrl.trim().toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            url.encodedPath != "/" || url.host !in setOf("127.0.0.1", "localhost") ||
            (url.scheme != "https" && !(debug && url.scheme == "http")) || !Regex("^[a-f0-9]{64}$").matches(token)) {
            throw TravelLiveException("INVALID_CONFIGURATION", "请填写本机服务地址与 64 位连接码；当前通过 USB 调试转发连接电脑。")
        }
        return url
    }
}

/** Excluded from cloud backup and device transfer; contains no supplier API keys. */
class TravelLiveConfiguration(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("travel_live_settings", Context.MODE_PRIVATE)
    fun load() = TravelLiveConfig(prefs.getString("base_url", "http://127.0.0.1:8794").orEmpty(), prefs.getString("token", "").orEmpty())
    fun save(value: TravelLiveConfig) {
        value.validatedUrl()
        prefs.edit { putString("base_url", value.baseUrl.trim().trimEnd('/')); putString("token", value.token.trim()) }
    }
    fun clear() { prefs.edit { clear() } }
}
