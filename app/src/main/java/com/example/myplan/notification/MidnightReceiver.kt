package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myplan.MyPlanApp
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.focus.CreditManager
import com.example.myplan.focus.service.UsageMonitorService
import kotlinx.coroutines.*

/**
 * 午夜重置触发器 — 三重保障之一（AlarmManager 00:01 定时）
 * 负责：重置计划完成状态 + 清零积分 + Focus 日切
 */
class MidnightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MyPlanApp

                // 1. 重置计划完成状态（循环计划的 isCompleted ← false）
                val planDb = PlanDatabase.getInstance(context)
                val planRepo = PlanRepository(planDb)
                planRepo.resetDailyCompletions()

                // 2. 清零积分 + 防重集合
                CreditManager.resetDaily(app)

                // 3. Focus 日切（使用时长/锁定状态重置）
                UsageMonitorService.dailyReset(context)

                // 4. 重新调度明天的午夜闹钟
                AlarmScheduler.scheduleMidnightReset(context)
            } catch (e: Exception) {
                // Log silently — don't crash the broadcast
            } finally {
                pendingResult.finish()
            }
        }
    }
}
