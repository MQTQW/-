package com.example.myplan.quickadd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.*

class QuickCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    "quick.INPUT" -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence("text")?.toString()?.let {
                        QuickCapture.receive(context, token, it)
                    }
                    "quick.CHOOSE" -> QuickCapture.choose(context, token, intent.getLongExtra("at", 0))
                    "quick.UNDO" -> QuickCapture.undo(context, token)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("QuickCapture", "Quick capture failed", e)
                QuickCaptureNotifications.showError(context, token, "保存未完成，请打开快记重试")
            } finally { pending.finish() }
        }
    }
}
