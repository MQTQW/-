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
import com.example.myplan.data.local.entity.PlanCompletion
import com.example.myplan.data.local.entity.CreditEntry
import com.example.myplan.data.local.dao.CompletionDao
import com.example.myplan.data.local.dao.CreditDao

@Database(
    entities = [PlanEntity::class, ReminderEntity::class, DailyCheckin::class, PlanCompletion::class, CreditEntry::class],
    version = 5,
    exportSchema = true
)
abstract class PlanDatabase : RoomDatabase() {

    abstract fun planDao(): PlanDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dailyCheckinDao(): DailyCheckinDao
    abstract fun completionDao(): CompletionDao
    abstract fun creditDao(): CreditDao

    companion object {
        @Volatile
        private var INSTANCE: PlanDatabase? = null

        fun getInstance(context: Context): PlanDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlanDatabase::class.java,
                    "my_plan.db"
                ).addMigrations(PlanMigrations.FROM_1_TO_2, PlanMigrations.FROM_2_TO_3,
                    PlanMigrations.FROM_3_TO_4, PlanMigrations.FROM_4_TO_5).build().also { INSTANCE = it }
            }
        }
    }
}
