package com.example.myplan.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credit_entries")
data class CreditEntry(@PrimaryKey val id: String, val date: String, val amount: Int)
