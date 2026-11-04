package com.example.myplan.travel.live

import android.content.Context
import androidx.core.content.edit
import com.example.myplan.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A private travel-service access code, never a provider API key. */
data class TravelLiveConfig(val baseUrl: String = "", val token: String = "") {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
    override fun toString() = "TravelLiveConfig(configured=$isConfigured)"
    fun validatedUrl(debug: Boolean = BuildConfig.DEBUG): HttpUrl {
        val url = baseUrl.trim().toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            url.encodedPath != "/" ||
            (url.scheme != "https" && !(debug && url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost"))) ||
            !Regex("^[a-f0-9]{64}$").matches(token)) {
            throw TravelLiveException("INVALID_CONFIGURATION", "请填写完整的 HTTPS 旅行服务地址与 64 位连接码，地址后不要附加路径或参数。")
        }
        return url
    }
}

/** Excluded from cloud backup and device transfer; contains no supplier API keys. */
class TravelLiveConfiguration(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("travel_live_settings", Context.MODE_PRIVATE)
    fun load() = TravelLiveConfig(prefs.getString("base_url", "").orEmpty(), prefs.getString("token", "").orEmpty())
    fun save(value: TravelLiveConfig) {
        value.validatedUrl()
        prefs.edit { putString("base_url", value.baseUrl.trim().trimEnd('/')); putString("token", value.token.trim()) }
    }
    fun clear() { prefs.edit { clear() } }
}
