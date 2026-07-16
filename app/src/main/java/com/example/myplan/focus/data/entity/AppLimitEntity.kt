package com.example.myplan.focus.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_limits")
data class AppLimitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String? = null,
    val appName: String = "",
    val categoryId: Long? = null,
    val dailyLimitMillis: Long = 0,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCategoryLimit: Boolean get() = categoryId != null
    val isAppLimit: Boolean get() = packageName != null
}
