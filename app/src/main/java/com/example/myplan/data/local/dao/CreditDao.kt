package com.example.myplan.data.local.dao

import androidx.room.*
import com.example.myplan.data.local.entity.CreditEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CreditEntry)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putOnce(entry: CreditEntry): Long

    @Query("SELECT * FROM credit_entries WHERE id = :id")
    suspend fun get(id: String): CreditEntry?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM credit_entries WHERE date = :date")
    suspend fun balance(date: String): Int

    @Query("SELECT COALESCE(SUM(amount), 0) FROM credit_entries WHERE date = :date")
    fun observeBalance(date: String): Flow<Int>
}
