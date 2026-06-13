package com.example.myplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_checkin")
data class DailyCheckin(
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: String,  // "2026-06-13"

    @ColumnInfo(name = "completed_count")
    val completedCount: Int = 0
)
