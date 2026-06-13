package com.example.myplan.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity

data class PlanWithReminders(
    @Embedded
    val plan: PlanEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "plan_id"
    )
    val reminders: List<ReminderEntity>
)
