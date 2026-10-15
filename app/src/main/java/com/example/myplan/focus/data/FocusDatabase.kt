package com.example.myplan.focus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myplan.focus.data.dao.*
import com.example.myplan.focus.data.entity.*

@Database(
    entities = [
        AppLimitEntity::class,
        CategoryEntity::class,
        TaskEntity::class,
        DailyUsageEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class FocusDatabase : RoomDatabase() {

    abstract fun appLimitDao(): AppLimitDao
    abstract fun categoryDao(): CategoryDao
    abstract fun taskDao(): TaskDao
    abstract fun usageLogDao(): UsageLogDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_usage ADD COLUMN isUnlocked INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile
        private var INSTANCE: FocusDatabase? = null

        fun getInstance(context: Context): FocusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FocusDatabase::class.java,
                    "focus.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
