package com.example.myplan.travel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TripEntity::class, TripStopEntity::class], version = 2, exportSchema = true)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun travelDao(): TravelDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN liveEvidenceJson TEXT")
            }
        }
        @Volatile private var instance: TravelDatabase? = null

        fun getInstance(context: Context): TravelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TravelDatabase::class.java, "travel.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
