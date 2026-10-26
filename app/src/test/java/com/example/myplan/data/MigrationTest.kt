package com.example.myplan.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.PlanMigrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class MigrationTest {
    @Test fun version6UpgradePreservesPlansHistoryAndCreditLedger() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT, category TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_completed INTEGER NOT NULL, repeat_mode TEXT NOT NULL, one_time_at INTEGER, creation_token TEXT, custom_days TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    db.execSQL("CREATE UNIQUE INDEX index_plans_creation_token ON plans(creation_token)")
                    db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plan_id INTEGER NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, is_enabled INTEGER NOT NULL, created_at INTEGER NOT NULL, legacy_repeat_mode TEXT, legacy_time INTEGER, schedule_version INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(plan_id) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_reminders_plan_id ON reminders(plan_id)")
                    db.execSQL("CREATE TABLE daily_checkin (date TEXT PRIMARY KEY NOT NULL, completed_count INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE plan_completions (planId INTEGER NOT NULL, date TEXT NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(planId, date))")
                    db.execSQL("CREATE TABLE credit_entries (id TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, amount INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO plans VALUES(1, '保留计划', NULL, '学习', 1, 1, 'EVERY_DAY', NULL, NULL, '', 100, 200)")
                    db.execSQL("INSERT INTO reminders VALUES(2, 1, 8, 30, 1, 100, NULL, NULL, 7)")
                    db.execSQL("INSERT INTO daily_checkin VALUES('2026-10-26', 1)")
                    db.execSQL("INSERT INTO plan_completions VALUES(1, '2026-10-26', 1)")
                    db.execSQL("INSERT INTO credit_entries VALUES('plan:1:2026-10-26', '2026-10-26', 1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, PlanDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(PlanMigrations.FROM_6_TO_7).build()
        try {
            assertEquals("保留计划", db.planDao().getPlanById(1)!!.name)
            assertEquals(7L, db.reminderDao().getReminderById(2)!!.scheduleVersion)
            assertEquals(1, db.creditDao().balance("2026-10-26"))
            assertTrue(db.completionDao().get(1, "2026-10-26")!!.completed)
            assertNull(db.focusSessionDao().current())
            assertTrue(db.focusSessionDao().allUnlocks().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyUpgradePreservesIndependentSingleAndWeeklyRules() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "legacy-${UUID.randomUUID()}"
        val timestamp = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 2); set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 30); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT, category TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_completed INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plan_id INTEGER NOT NULL, time INTEGER NOT NULL, repeat_mode TEXT NOT NULL, is_enabled INTEGER NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(plan_id) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_reminders_plan_id ON reminders(plan_id)")
                    db.execSQL("INSERT INTO plans VALUES(1, '旧版计划', NULL, '学习', 1, 0, 100, 200)")
                    db.execSQL("INSERT INTO reminders VALUES(2, 1, $timestamp, 'NONE', 1, 100)")
                    db.execSQL("INSERT INTO reminders VALUES(3, 1, $timestamp, 'WEEKLY', 1, 100)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, PlanDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(PlanMigrations.FROM_1_TO_2, PlanMigrations.FROM_2_TO_3, PlanMigrations.FROM_3_TO_4, PlanMigrations.FROM_4_TO_5, PlanMigrations.FROM_5_TO_6, PlanMigrations.FROM_6_TO_7).build()
        try {
            val single = db.reminderDao().getReminderById(2)!!
            val weekly = db.reminderDao().getReminderById(3)!!
            assertEquals("NONE", single.legacyRepeatMode)
            assertEquals(timestamp, single.legacyTime)
            assertEquals("WEEKLY", weekly.legacyRepeatMode)
            val scheduler = com.example.myplan.notification.AlarmScheduler(context)
            val plan = db.planDao().getPlanById(1)!!
            assertEquals(timestamp, scheduler.nextFireTime(plan, single))
            assertNull(scheduler.nextFireTime(plan, single, after = timestamp + 1))
            assertTrue(scheduler.nextFireTime(plan, weekly, after = timestamp + 1)!! > timestamp)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun focusUpgradePreservesUsageAndLimitConfiguration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "focus-migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE app_limits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT, appName TEXT NOT NULL, categoryId INTEGER, dailyLimitMillis INTEGER NOT NULL, isEnabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, `key` TEXT NOT NULL, isPreset INTEGER NOT NULL, dailyLimitMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, isCompleted INTEGER NOT NULL, isRecurring INTEGER NOT NULL, unlockCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, lastCompletedDate TEXT)")
                    db.execSQL("CREATE TABLE daily_usage (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, appName TEXT NOT NULL, date TEXT NOT NULL, totalTimeMillis INTEGER NOT NULL, isLocked INTEGER NOT NULL, categoryId INTEGER, lastUpdated INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO app_limits VALUES(1, 'video.a', '视频', 1, 60000, 1, 100)")
                    db.execSQL("INSERT INTO daily_usage VALUES(1, 'video.a', '视频', '2026-10-15', 30000, 1, 1, 100)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, com.example.myplan.focus.data.FocusDatabase::class.java, name)
            .addMigrations(com.example.myplan.focus.data.FocusDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            assertEquals(60000, db.appLimitDao().getLimitForPackage("video.a")!!.dailyLimitMillis)
            val usage = db.usageLogDao().getUsageForPackageAndDate("video.a", "2026-10-15")!!
            assertEquals(30000, usage.totalTimeMillis)
            assertTrue(usage.isLocked)
            assertFalse(usage.isUnlocked)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun version3UpgradePreservesPlansRemindersAndHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT, category TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_completed INTEGER NOT NULL, repeat_mode TEXT NOT NULL, custom_days TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plan_id INTEGER NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, is_enabled INTEGER NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(plan_id) REFERENCES plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_reminders_plan_id ON reminders(plan_id)")
                    db.execSQL("CREATE TABLE daily_checkin (date TEXT PRIMARY KEY NOT NULL, completed_count INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO plans VALUES(1, '已有计划', NULL, '学习', 1, 1, 'CUSTOM', '1,3,5', 100, 200)")
                    db.execSQL("INSERT INTO reminders VALUES(2, 1, 8, 30, 1, 100)")
                    db.execSQL("INSERT INTO daily_checkin VALUES('2026-10-10', 5)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, PlanDatabase::class.java, name)
            .addMigrations(PlanMigrations.FROM_3_TO_4, PlanMigrations.FROM_4_TO_5, PlanMigrations.FROM_5_TO_6, PlanMigrations.FROM_6_TO_7).allowMainThreadQueries().build()
        try {
            assertEquals("已有计划", db.planDao().getPlanById(1)!!.name)
            assertEquals(30, db.reminderDao().getReminderById(2)!!.minute)
            assertEquals(5, db.dailyCheckinDao().getByDate("2026-10-10")!!.completedCount)
            assertEquals(0, db.creditDao().balance(java.time.LocalDate.now().toString()))
            assertTrue(db.completionDao().get(1, java.time.LocalDate.now().toString())!!.completed)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
