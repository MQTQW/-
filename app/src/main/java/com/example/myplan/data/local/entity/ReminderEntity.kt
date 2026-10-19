package com.example.myplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["plan_id"])]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "plan_id")
    val planId: Long,

    @ColumnInfo(name = "hour")
    val hour: Int,  // 0-23

    @ColumnInfo(name = "minute")
    val minute: Int,  // 0-59

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "legacy_repeat_mode")
    val legacyRepeatMode: String? = null,
    @ColumnInfo(name = "legacy_time")
    val legacyTime: Long? = null,
    @ColumnInfo(name = "schedule_version", defaultValue = "0")
    val scheduleVersion: Long = 0
)
