package com.example.myplan.data.local.entity

import androidx.room.Entity

/** One completion per plan and local date; retained when a plan is deleted. */
@Entity(tableName = "plan_completions", primaryKeys = ["planId", "date"])
data class PlanCompletion(val planId: Long, val date: String, val completed: Boolean)
