package com.example.myplan.focus.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val key: String,
    val isPreset: Boolean = false,
    val dailyLimitMillis: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
