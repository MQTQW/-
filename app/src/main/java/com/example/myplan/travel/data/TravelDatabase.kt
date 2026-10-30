package com.example.myplan.travel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, TripStopEntity::class], version = 1, exportSchema = true)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun travelDao(): TravelDao

    companion object {
        @Volatile private var instance: TravelDatabase? = null

        fun getInstance(context: Context): TravelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TravelDatabase::class.java, "travel.db")
                .build().also { instance = it }
        }
    }
}
