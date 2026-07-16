package com.example.myplan.focus.data.repository

import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.CategoryEntity
import com.example.myplan.focus.util.FocusConstants
import kotlinx.coroutines.flow.Flow

class LimitRepository(
    private val database: FocusDatabase
) {
    private val limitDao = database.appLimitDao()
    private val categoryDao = database.categoryDao()

    // ===== App Limits =====

    fun getAllAppLimits(): Flow<List<AppLimitEntity>> = limitDao.getAllEnabled()

    fun getAllLimits(): Flow<List<AppLimitEntity>> = limitDao.getAll()

    suspend fun getLimitForPackage(packageName: String): AppLimitEntity? =
        limitDao.getLimitForPackage(packageName)

    suspend fun getAllAppLimitsSync(): List<AppLimitEntity> =
        limitDao.getAllAppLimits()

    suspend fun getCategoryLimitsSync(): List<AppLimitEntity> =
        limitDao.getAllCategoryLimits()

    suspend fun insertLimit(limit: AppLimitEntity): Long =
        limitDao.insert(limit)

    suspend fun updateLimit(limit: AppLimitEntity) =
        limitDao.update(limit)

    suspend fun deleteLimit(id: Long) =
        limitDao.deleteById(id)

    suspend fun setLimitEnabled(id: Long, enabled: Boolean) =
        limitDao.setEnabled(id, enabled)

    // ===== Categories =====

    fun getAllCategories(): Flow<List<CategoryEntity>> = categoryDao.getAll()

    suspend fun getCategoryById(id: Long): CategoryEntity? =
        categoryDao.getById(id)

    suspend fun getCategoryByKey(key: String): CategoryEntity? =
        categoryDao.getByKey(key)

    suspend fun insertCategory(category: CategoryEntity): Long =
        categoryDao.insert(category)

    suspend fun updateCategory(category: CategoryEntity) =
        categoryDao.update(category)

    suspend fun deleteCategory(id: Long) =
        categoryDao.deleteById(id)

    suspend fun updateCategoryLimit(id: Long, limitMillis: Long) =
        categoryDao.updateDailyLimit(id, limitMillis)

    // ===== Preset Categories =====

    suspend fun ensurePresetCategories() {
        for ((key, name) in FocusConstants.DEFAULT_CATEGORIES) {
            val existing = categoryDao.getByKey(key)
            if (existing == null) {
                categoryDao.insert(
                    CategoryEntity(
                        name = name,
                        key = key,
                        isPreset = true
                    )
                )
            }
        }
    }
}
