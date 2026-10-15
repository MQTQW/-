package com.example.myplan.focus

import com.example.myplan.MyPlanApp

object CreditManager {
    suspend fun awardTaskCompletion(app: MyPlanApp, taskId: Long, count: Int): Boolean =
        app.creditRepository.awardTask(taskId, count)

    suspend fun consumeCredit(app: MyPlanApp): Boolean = app.consumeCredit()

    fun resetDaily(app: MyPlanApp) { app.creditRepository.refreshDate() }
}
