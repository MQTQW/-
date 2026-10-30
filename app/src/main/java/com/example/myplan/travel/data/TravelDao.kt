package com.example.myplan.travel.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelDao {
    @Transaction
    @Query("SELECT * FROM trips ORDER BY startDate, createdAt DESC, id")
    fun observeTrips(): Flow<List<TripWithStops>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<TripWithStops?>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripWithStops?

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTripEntity(id: Long): TripEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId ORDER BY dayIndex, position, id")
    suspend fun getStops(tripId: Long): List<TripStopEntity>

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId AND id = :stopId")
    suspend fun getStop(tripId: Long, stopId: Long): TripStopEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStop(stop: TripStopEntity): Long

    @Update
    suspend fun updateStop(stop: TripStopEntity)

    @Query("DELETE FROM trip_stops WHERE tripId = :tripId AND id = :stopId")
    suspend fun deleteStop(tripId: Long, stopId: Long)

    @Query("DELETE FROM trip_stops WHERE tripId = :tripId")
    suspend fun deleteStops(tripId: Long)
}
