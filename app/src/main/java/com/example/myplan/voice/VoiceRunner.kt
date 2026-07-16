package com.example.myplan.voice

import android.app.Activity
import android.util.Log
import com.example.myplan.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VoiceRunner(private val activity: Activity) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: AudioRecorder? = null
    private var conversationActive: Boolean = false

    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase

    companion object {
        private const val TAG = "VoiceRunner"
        private val VOLC_API_KEY = BuildConfig.VOLC_API_KEY
        private val DEEPSEEK_KEY = BuildConfig.DEEPSEEK_KEY
    }

    fun start() {
        Log.d(TAG, "Starting")
        CloudSttService.init(VOLC_API_KEY)
        CloudTtsService.init(VOLC_API_KEY)
        VoiceApiService.init(DEEPSEEK_KEY)
        recorder = AudioRecorder(activity).apply {
            setListener(object : AudioRecorder.RecordListener {
                override fun onSpeechStarted() {
                    Log.d(TAG, "User speaking...")
                    _phase.value = VoicePhase.LISTENING
                }
                override fun onSpeechEnded(pcmData: ByteArray) {
                    Log.d(TAG, "Speech ended, ${pcmData.size} bytes")
                    recorder?.stopListening()
                    scope.launch { processAudio(pcmData) }
                }
                override fun onSilenceTimeout() { Log.d(TAG, "Silence timeout") }
                override fun onError(msg: String) { Log.e(TAG, "Recorder error: $msg") }
            })
        }
    }

    fun stop() {
        CloudTtsService.stop()
        recorder?.stopListening()
        scope.cancel()
    }

    fun startListening() {
        if (!recorder!!.isPermissionGranted()) { Log.e(TAG, "No record permission"); return }
        recorder?.startListening()
    }

    private suspend fun processAudio(pcmData: ByteArray) {
        val result = CloudSttService.recognize(pcmData)
        if (result == null) {
            speakAndListen("抱歉，识别超时，请再试一次。")
            return
        }
        val text = result.text.trim()
        if (text.isBlank()) {
            speakAndListen("我没听清，请再说一遍。")
            return
        }
        Log.d(TAG, "Recognized: $text")
        handleUserText(text)
    }

    private fun handleUserText(text: String) {
        // Start DeepSeek conversation on plan-related input
        if (!conversationActive) {
            if (text.contains("创建") || text.contains("计划") || text.contains("新建") || text.contains("提醒") || text.contains("定一个") || text.contains("加个")) {
                conversationActive = true
                _phase.value = VoicePhase.PROCESSING
                DeepSeekConversation.init(DEEPSEEK_KEY)
                DeepSeekConversation.resetConversation()
            } else {
                recorder?.startListening()
                return
            }
        } else {
            _phase.value = VoicePhase.PROCESSING
        }

        scope.launch {
            val result = DeepSeekConversation.processUserInput(text)
            if (result == null) {
                _phase.value = VoicePhase.LISTENING
                speakAndListen("少爷稍等，网络不太好，再说一次。")
                return@launch
            }

            if (result.isCancel) {
                DeepSeekConversation.resetConversation()
                conversationActive = false
                _phase.value = VoicePhase.IDLE
                speakFinal(result.reply)
                return@launch
            }

            if (result.isDone && result.planData != null) {
                createPlan(result.planData)
                DeepSeekConversation.resetConversation()
                conversationActive = false
                _phase.value = VoicePhase.IDLE
                speakFinal(result.reply)
                return@launch
            }

            speakAndListen(result.reply)
        }
    }

    private fun speakAndListen(text: String) {
        _phase.value = VoicePhase.SPEAKING
        CloudTtsService.speak(
            text,
            done = {
                _phase.value = VoicePhase.LISTENING
                scope.launch { delay(300); recorder?.startListening() }
            },
            error = { Log.e(TAG, "TTS error: $it"); recorder?.startListening() }
        )
    }

    private fun speakFinal(text: String) {
        _phase.value = VoicePhase.SPEAKING
        CloudTtsService.speak(
            text,
            done = { _phase.value = VoicePhase.IDLE },
            error = { _phase.value = VoicePhase.IDLE }
        )
    }

    private fun createPlan(data: PlanVoiceData) {
        Log.d(TAG, "Creating: ${data.name}")
        scope.launch(Dispatchers.IO) {
            try {
                val db = com.example.myplan.data.local.PlanDatabase.getInstance(activity)
                val repo = com.example.myplan.data.repository.PlanRepository(db)
                val plan = com.example.myplan.data.local.entity.PlanEntity(
                    name = data.name, category = data.category,
                    repeatMode = data.repeatMode, customDays = data.customDays.joinToString(","),
                    isEnabled = true, isCompleted = false,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
                )
                val reminders = data.reminderTimes.map {
                    val parts = it.split(":")
                    com.example.myplan.data.local.entity.ReminderEntity(
                        planId = 0L, hour = parts[0].toInt(), minute = parts[1].toInt(),
                        isEnabled = true, createdAt = System.currentTimeMillis()
                    )
                }
                repo.savePlanWithReminders(plan, reminders)
                Log.d(TAG, "Created!")
            } catch (e: Exception) { Log.e(TAG, "Failed", e) }
        }
    }

}
