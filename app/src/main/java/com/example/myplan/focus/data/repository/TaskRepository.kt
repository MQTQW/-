package com.example.myplan.focus.data.repository

import com.example.myplan.focus.data.FocusDatabase
import com.example.myplan.focus.data.entity.TaskEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*

class TaskRepository(
    private val database: FocusDatabase
) {
    private val dao = database.taskDao()

    fun getAllTasks(): Flow<List<TaskEntity>> = dao.getAll()

    fun getUncompletedTasks(): Flow<List<TaskEntity>> = dao.getUncompleted()

    fun getUncompletedCount(): Flow<Int> = dao.getUncompletedCount()

    suspend fun getTaskById(id: Long): TaskEntity? = dao.getById(id)

    suspend fun insertTask(task: TaskEntity): Long = dao.insert(task)

    suspend fun updateTask(task: TaskEntity) = dao.update(task)

    suspend fun deleteTask(id: Long) = dao.deleteById(id)

    suspend fun markCompleted(id: Long, date: String) = dao.markCompleted(id = id, date = date)

    suspend fun markUncompleted(id: Long) = dao.markUncompleted(id)

    suspend fun completeTask(taskId: Long, date: String): Int {
        val task = dao.getById(taskId) ?: return 0
        if (task.lastCompletedDate == date) {
            dao.markCompleted(id = taskId, date = date)
            return 0
        }
        dao.markCompleted(id = taskId, date = date)
        return task.unlockCount
    }

    suspend fun resetDailyTasks() {
        dao.resetRecurringTasks()
    }
}
