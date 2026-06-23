package com.example.myplan.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myplan.data.local.dao.DailyCheckinDao
import com.example.myplan.data.local.dao.PlanDao
import com.example.myplan.data.local.dao.ReminderDao
import com.example.myplan.data.local.entity.DailyCheckin
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity

@Database(
    entities = [PlanEntity::class, ReminderEntity::class, DailyCheckin::class],
    version = 3,
    exportSchema = false
)
abstract class PlanDatabase : RoomDatabase() {

    abstract fun planDao(): PlanDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dailyCheckinDao(): DailyCheckinDao

    companion object {
        @Volatile
        private var INSTANCE: PlanDatabase? = null

        fun getInstance(context: Context): PlanDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlanDatabase::class.java,
                    "my_plan.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
