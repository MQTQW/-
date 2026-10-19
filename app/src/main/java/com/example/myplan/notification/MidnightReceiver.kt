package com.example.myplan.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myplan.data.DailyRollover
import kotlinx.coroutines.*

class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { DailyRollover.ensureToday(context, forceSchedule = true) }
            catch (e: Exception) { Log.e("MidnightReceiver", "Daily reset failed", e) }
            finally { pending.finish() }
        }
    }
}
