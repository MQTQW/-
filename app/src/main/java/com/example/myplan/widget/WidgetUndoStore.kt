package com.example.myplan.widget

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

data class WidgetUndo(val planId: Long, val updatedAt: Long, val date: String)

/** Scoped to a widget; compare-and-set on the plan makes undo harmless after another edit. */
class WidgetUndoStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget_undo", Context.MODE_PRIVATE)
    fun get(widgetId: Int): WidgetUndo? = try {
        prefs.getString(widgetId.toString(), null)?.let {
            val j = JSONObject(it)
            WidgetUndo(j.getLong("id"), j.getLong("updatedAt"), j.getString("date"))
        }
    } catch (_: Exception) { null }
    fun put(widgetId: Int, undo: WidgetUndo) {
        prefs.edit { putString(widgetId.toString(), JSONObject().put("id", undo.planId)
            .put("updatedAt", undo.updatedAt).put("date", undo.date).toString()) }
    }
    fun clear(widgetId: Int) { prefs.edit { remove(widgetId.toString()) } }
}
