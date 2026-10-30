package com.example.myplan.travel.data

import androidx.room.Embedded
import androidx.room.Relation

data class TripWithStops(
    @Embedded val trip: TripEntity,
    @Relation(parentColumn = "id", entityColumn = "tripId") val stops: List<TripStopEntity>
)
