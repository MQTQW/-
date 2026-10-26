package com.example.myplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "plans", indices = [Index(value = ["creation_token"], unique = true)])
data class PlanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "category")
    val category: String = "日常",

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "repeat_mode")
    val repeatMode: String = "EVERY_DAY",  // ONCE | EVERY_DAY | WEEKDAYS | CUSTOM

    @ColumnInfo(name = "one_time_at")
    val oneTimeAt: Long? = null,

    @ColumnInfo(name = "creation_token")
    val creationToken: String? = null,

    @ColumnInfo(name = "custom_days")
    val customDays: String = "",  // "1,3,5" = 周一、周三、周五（仅 CUSTOM 模式使用）

    @ColumnInfo(name = "suggested_focus_minutes", defaultValue = "25")
    val suggestedFocusMinutes: Int = 25,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
