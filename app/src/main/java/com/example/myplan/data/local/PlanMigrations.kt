package com.example.myplan.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

object PlanMigrations {
    val FROM_7_TO_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE plans ADD COLUMN suggested_focus_minutes INTEGER NOT NULL DEFAULT 25")
            db.execSQL("""CREATE TABLE IF NOT EXISTS plan_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plan_id INTEGER NOT NULL,
                position INTEGER NOT NULL, title TEXT NOT NULL, focus_minutes INTEGER NOT NULL,
                is_completed INTEGER NOT NULL, completed_date TEXT,
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent())
            db.execSQL("CREATE INDEX index_plan_steps_plan_id ON plan_steps(plan_id)")
            db.execSQL("ALTER TABLE focus_sessions ADD COLUMN stepId INTEGER")
            db.execSQL("ALTER TABLE focus_sessions ADD COLUMN stepTitle TEXT")
        }
    }

    val FROM_6_TO_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS focus_sessions (
                id INTEGER NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, planId INTEGER NOT NULL,
                planName TEXT NOT NULL, planUpdatedAt INTEGER NOT NULL, date TEXT NOT NULL, status TEXT NOT NULL,
                durationMillis INTEGER NOT NULL, remainingMillis INTEGER NOT NULL, blockedPackages TEXT NOT NULL,
                startedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, anchorElapsedRealtime INTEGER NOT NULL,
                bootCount INTEGER NOT NULL)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS timed_unlocks (
                requestId TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, date TEXT NOT NULL,
                durationMillis INTEGER NOT NULL, cost INTEGER NOT NULL, startedAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL, startedElapsedRealtime INTEGER NOT NULL,
                expiresElapsedRealtime INTEGER NOT NULL, bootCount INTEGER NOT NULL)""".trimIndent())
        }
    }
    val FROM_5_TO_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE plans ADD COLUMN one_time_at INTEGER")
            db.execSQL("ALTER TABLE plans ADD COLUMN creation_token TEXT")
            db.execSQL("CREATE UNIQUE INDEX index_plans_creation_token ON plans(creation_token)")
        }
    }
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN schedule_version INTEGER NOT NULL DEFAULT 0")
        }
    }
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS daily_checkin (date TEXT NOT NULL PRIMARY KEY, completed_count INTEGER NOT NULL)")
        }
    }

    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE plans ADD COLUMN repeat_mode TEXT NOT NULL DEFAULT 'EVERY_DAY'")
            db.execSQL("ALTER TABLE plans ADD COLUMN custom_days TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE legacy_reminders AS SELECT id, time, repeat_mode FROM reminders")
            db.execSQL("""CREATE TABLE reminders_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plan_id INTEGER NOT NULL,
                hour INTEGER NOT NULL, minute INTEGER NOT NULL, is_enabled INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent())
            // Legacy timestamps represent local reminder times, not UTC clock hours.
            db.execSQL("""INSERT INTO reminders_new
                SELECT id, plan_id,
                CAST(strftime('%H', time / 1000, 'unixepoch', 'localtime') AS INTEGER),
                CAST(strftime('%M', time / 1000, 'unixepoch', 'localtime') AS INTEGER),
                is_enabled, created_at FROM reminders""".trimIndent())
            db.execSQL("DROP TABLE reminders")
            db.execSQL("ALTER TABLE reminders_new RENAME TO reminders")
            db.execSQL("CREATE INDEX index_reminders_plan_id ON reminders(plan_id)")
        }
    }

    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN legacy_repeat_mode TEXT")
            db.execSQL("ALTER TABLE reminders ADD COLUMN legacy_time INTEGER")
            val legacy = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'legacy_reminders'").use { it.moveToFirst() }
            if (legacy) {
                db.execSQL("UPDATE reminders SET legacy_repeat_mode = (SELECT repeat_mode FROM legacy_reminders WHERE legacy_reminders.id = reminders.id), legacy_time = (SELECT time FROM legacy_reminders WHERE legacy_reminders.id = reminders.id)")
                db.execSQL("DROP TABLE legacy_reminders")
            }
            db.execSQL("CREATE TABLE plan_completions (planId INTEGER NOT NULL, date TEXT NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(planId, date))")
            db.execSQL("CREATE TABLE credit_entries (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, amount INTEGER NOT NULL)")
            // Preserve today's existing completion flags without inventing historical detail or rewards.
            db.execSQL("INSERT INTO plan_completions SELECT id, ?, 1 FROM plans WHERE is_completed = 1",
                arrayOf<Any>(LocalDate.now().toString()))
        }
    }
}
