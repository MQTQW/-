package com.example.myplan.travel.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

data class TripStopDraft(
    val id: Long = 0,
    val dayIndex: Int,
    val position: Int = 0,
    val title: String,
    val location: String = "",
    val startMinute: Int,
    val durationMinutes: Int = 60,
    val notes: String = "",
    val isCompleted: Boolean = false
)

@Entity(
    tableName = "trip_stops",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class TripStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val dayIndex: Int,
    val position: Int,
    val title: String,
    val location: String = "",
    val startMinute: Int,
    val durationMinutes: Int = 60,
    val notes: String = "",
    val isCompleted: Boolean = false
)
