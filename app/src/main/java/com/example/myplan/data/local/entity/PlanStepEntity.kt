package com.example.myplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A zero id denotes a new step; existing ids keep their completion state when edited. */
data class PlanStepDraft(
    val id: Long = 0,
    val title: String,
    val focusMinutes: Int = 25
)

@Entity(
    tableName = "plan_steps",
    foreignKeys = [ForeignKey(
        entity = PlanEntity::class,
        parentColumns = ["id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("plan_id")]
)
data class PlanStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "plan_id") val planId: Long,
    val position: Int,
    val title: String,
    @ColumnInfo(name = "focus_minutes") val focusMinutes: Int = 25,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false,
    @ColumnInfo(name = "completed_date") val completedDate: String? = null
) {
    fun isDoneOn(repeatMode: String, date: String): Boolean =
        isCompleted && (repeatMode == "ONCE" || completedDate == date)
}
