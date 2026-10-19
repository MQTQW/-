package com.example.myplan.quickadd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CaptureRecord(val token: String, val draft: QuickDraft, val status: String = "pending",
    val planId: Long = 0, val updatedAt: Long = System.currentTimeMillis())

/** Survives process death between notification input, time selection and saving. */
class QuickCaptureStore(context: Context) {
    private val prefs = context.getSharedPreferences("quick_capture", Context.MODE_PRIVATE)

    fun get(token: String): CaptureRecord? = try {
        prefs.getString(token, null)?.let { raw ->
            val j = JSONObject(raw)
            CaptureRecord(token, QuickDraft(j.getString("title"), j.getString("mode"), j.optString("days"),
                if (j.has("at")) j.getLong("at") else null, j.getInt("hour"), j.getInt("minute"),
                if (j.has("date")) j.getString("date") else null,
                if (j.has("issue")) j.getString("issue") else null,
                j.getJSONArray("alternatives").let { a -> (0 until a.length()).map { a.getLong(it) } }),
                j.getString("status"), j.getLong("planId"), j.getLong("updatedAt"))
        }
    } catch (_: Exception) { null }

    fun put(record: CaptureRecord) {
        val d = record.draft
        val j = JSONObject().put("title", d.title).put("mode", d.repeatMode).put("days", d.customDays)
            .put("at", d.at).put("hour", d.hour).put("minute", d.minute).put("date", d.date)
            .put("issue", d.issue).put("alternatives", JSONArray(d.alternatives))
            .put("status", record.status).put("planId", record.planId).put("updatedAt", record.updatedAt)
        check(prefs.edit().putString(record.token, j.toString()).commit()) { "暂时无法保存快记，请重试" }
    }

    fun prune() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val edit = prefs.edit()
        prefs.all.keys.forEach { key -> if ((get(key)?.updatedAt ?: 0) < cutoff) edit.remove(key) }
        edit.apply()
    }
}
