package com.example.myplan.data

import androidx.room.withTransaction
import com.example.myplan.data.local.PlanDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Portable plan export; API keys and microphone data are never part of the backup. */
object PlanBackup {
    suspend fun export(db: PlanDatabase): String = db.withTransaction {
        val plans = JSONArray()
        db.planDao().getAllPlansSync().forEach { plan ->
            val reminders = JSONArray()
            db.reminderDao().getRemindersByPlanId(plan.id).forEach { rem ->
                reminders.put(JSONObject().apply {
                    put("id", rem.id); put("hour", rem.hour); put("minute", rem.minute)
                    put("enabled", rem.isEnabled); put("createdAt", rem.createdAt)
                    put("legacyRepeatMode", rem.legacyRepeatMode ?: JSONObject.NULL)
                    put("legacyTime", rem.legacyTime ?: JSONObject.NULL)
                })
            }
            plans.put(JSONObject().apply {
                put("id", plan.id); put("name", plan.name); put("description", plan.description ?: JSONObject.NULL)
                put("category", plan.category); put("enabled", plan.isEnabled); put("completed", plan.isCompleted)
                put("repeatMode", plan.repeatMode); put("customDays", plan.customDays)
                put("createdAt", plan.createdAt); put("updatedAt", plan.updatedAt); put("reminders", reminders)
            })
        }
        val checkins = JSONArray()
        db.dailyCheckinDao().getAllForBackup().forEach {
            checkins.put(JSONObject().put("date", it.date).put("completedCount", it.completedCount))
        }
        val completions = JSONArray()
        db.completionDao().getAll().forEach {
            completions.put(JSONObject().put("planId", it.planId).put("date", it.date).put("completed", it.completed))
        }
        JSONObject().put("format", "my-plan-backup").put("version", 1)
            .put("exportedAt", Instant.now().toString()).put("plans", plans)
            .put("checkins", checkins).put("completions", completions).toString(2)
    }
}
