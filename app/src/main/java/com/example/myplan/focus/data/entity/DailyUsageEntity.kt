package com.example.myplan.focus.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val date: String,
    val totalTimeMillis: Long = 0,
    val isLocked: Boolean = false,
    val isUnlocked: Boolean = false,
    val categoryId: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
