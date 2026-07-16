package com.example.myplan.focus.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val isRecurring: Boolean = false,
    val unlockCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val lastCompletedDate: String? = null
)
