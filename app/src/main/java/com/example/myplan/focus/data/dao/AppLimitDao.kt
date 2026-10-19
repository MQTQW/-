package com.example.myplan.focus.data.dao

import androidx.room.*
import com.example.myplan.focus.data.entity.AppLimitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLimitDao {

    @Query("SELECT * FROM app_limits WHERE isEnabled = 1 ORDER BY createdAt DESC")
    fun getAllEnabled(): Flow<List<AppLimitEntity>>

    @Query("SELECT * FROM app_limits ORDER BY createdAt DESC")
    fun getAll(): Flow<List<AppLimitEntity>>

    @Query("SELECT * FROM app_limits WHERE packageName = :packageName AND isEnabled = 1 LIMIT 1")
    suspend fun getLimitForPackage(packageName: String): AppLimitEntity?

    @Query("SELECT * FROM app_limits WHERE categoryId = :categoryId AND isEnabled = 1")
    suspend fun getLimitsForCategory(categoryId: Long): List<AppLimitEntity>

    @Query("SELECT * FROM app_limits WHERE packageName IS NOT NULL AND isEnabled = 1")
    suspend fun getAllAppLimits(): List<AppLimitEntity>

    @Query("SELECT * FROM app_limits WHERE packageName IS NULL AND categoryId IS NOT NULL AND isEnabled = 1")
    suspend fun getAllCategoryLimits(): List<AppLimitEntity>

    @Query("SELECT categoryId FROM app_limits WHERE packageName = :packageName AND categoryId IS NOT NULL ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getCategoryForPackage(packageName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(limit: AppLimitEntity): Long

    @Update
    suspend fun update(limit: AppLimitEntity)

    @Delete
    suspend fun delete(limit: AppLimitEntity)

    @Query("DELETE FROM app_limits WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE app_limits SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
