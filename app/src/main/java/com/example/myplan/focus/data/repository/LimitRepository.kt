package com.example.myplan.focus.data.repository

import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.AppLimitEntity
import com.example.myplan.focus.data.entity.CategoryEntity
import com.example.myplan.focus.util.FocusConstants
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import com.example.myplan.focus.FocusLimitPolicy
import java.time.LocalDate

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

    suspend fun insertLimit(limit: AppLimitEntity): Long = database.withTransaction {
        val id = limitDao.insert(limit)
        if (limit.packageName != null && limit.categoryId != null)
            database.usageLogDao().setCategory(limit.packageName, limit.categoryId)
        reconcileLocks()
        id
    }

    suspend fun updateLimit(limit: AppLimitEntity) = database.withTransaction {
        limitDao.update(limit)
        if (limit.packageName != null && limit.categoryId != null)
            database.usageLogDao().setCategory(limit.packageName, limit.categoryId)
        reconcileLocks()
    }

    suspend fun deleteLimit(id: Long) = database.withTransaction {
        limitDao.deleteById(id)
        reconcileLocks()
    }

    suspend fun setLimitEnabled(id: Long, enabled: Boolean) = database.withTransaction {
        limitDao.setEnabled(id, enabled)
        reconcileLocks()
    }

    suspend fun reconcileLocks(date: String = LocalDate.now().toString()): Set<String> = database.withTransaction {
        val usage = database.usageLogDao().getUsageForDateSync(date)
        val rules = limitDao.getAllAppLimits() + limitDao.getAllCategoryLimits()
        val desired = FocusLimitPolicy.lockedPackages(usage, rules)
        usage.forEach { row ->
            val locked = row.packageName in desired
            if (row.isLocked != locked) database.usageLogDao().setLocked(row.id, locked)
        }
        desired
    }

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
