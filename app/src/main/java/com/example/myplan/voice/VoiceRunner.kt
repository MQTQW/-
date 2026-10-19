package com.example.myplan.voice

import android.app.Activity
import android.util.Log
import android.widget.Toast
import com.example.myplan.BuildConfig
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class VoiceUiState(val transcript: String = "", val reply: String = "", val pendingPlan: PlanVoiceData? = null,
    val isSaving: Boolean = false, val saved: Boolean = false)

class VoiceRunner(private val activity: Activity) {
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: AudioRecorder? = null
    private var active = false
    private var conversation = DeepSeekConversation()
    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui

    fun start() {
        if (recorder != null) return
        CloudSttService.init(BuildConfig.VOLC_API_KEY)
        CloudTtsService.init(BuildConfig.VOLC_API_KEY)
        recorder = AudioRecorder(activity).apply {
            setListener(object : AudioRecorder.RecordListener {
                override fun onSpeechStarted() { if (active) _phase.value = VoicePhase.LISTENING }
                override fun onSpeechEnded(pcmData: ByteArray) {
                    if (!active) return
                    recorder?.stopListening()
                    scope.launch { processAudio(pcmData) }
                }
                override fun onSilenceTimeout() { scope.launch { stop() } }
                override fun onError(msg: String) {
                    Log.e("VoiceRunner", msg)
                    scope.launch { Toast.makeText(activity, "这次没有听清，请重试", Toast.LENGTH_SHORT).show(); stop() }
                }
            })
        }
    }

    fun startListening() {
        if (active || _phase.value != VoicePhase.IDLE) { stop(); return }
        if (BuildConfig.VOLC_API_KEY.isBlank() || BuildConfig.DEEPSEEK_KEY.isBlank()) {
            Toast.makeText(activity, "语音服务暂不可用，可以先手动创建计划", Toast.LENGTH_LONG).show()
            return
        }
        start()
        if (recorder?.isPermissionGranted() != true) return
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        conversation = DeepSeekConversation().apply { init(BuildConfig.DEEPSEEK_KEY) }
        _ui.value = VoiceUiState()
        active = true
        _phase.value = VoicePhase.LISTENING
        recorder?.startListening()
    }

    fun stop() {
        active = false
        scope.cancel()
        recorder?.stopListening()
        CloudTtsService.stop()
        _phase.value = VoicePhase.IDLE
        _ui.value = VoiceUiState()
    }

    fun cancelFromUi() {
        if (!_ui.value.isSaving) stop()
    }

    fun confirmPlan() {
        if (_ui.value.pendingPlan == null) return
        val data = conversation.confirmPending("确认") ?: return
        active = false
        recorder?.stopListening()
        CloudTtsService.stop()
        _ui.update { it.copy(pendingPlan = null, isSaving = true) }
        _phase.value = VoicePhase.PROCESSING
        scope.launch { savePlan(data) }
    }

    private suspend fun processAudio(pcmData: ByteArray) {
        _phase.value = VoicePhase.PROCESSING
        val text = CloudSttService.recognize(pcmData)?.text?.trim()
        currentCoroutineContext().ensureActive()
        if (text.isNullOrBlank()) { speakAndListen("没听清，请再说一次。"); return }
        _ui.update { it.copy(transcript = text, pendingPlan = null) }
        if (text in setOf("取消", "算了", "退出", "停止")) { stop(); return }
        val result = conversation.processUserInput(text)
        currentCoroutineContext().ensureActive()
        if (result == null) { speakAndListen("网络不太好，请再试一次。"); return }
        _ui.update { it.copy(reply = result.reply, pendingPlan = conversation.pendingPlan()) }
        if (result.isCancel) { finishSpeaking(result.reply); return }
        if (result.isDone && result.planData != null) { savePlan(result.planData); return }
        speakAndListen(result.reply)
    }

    private suspend fun savePlan(data: PlanVoiceData) {
        _ui.update { it.copy(isSaving = true) }
        try {
            withContext(Dispatchers.IO) {
                val repo = PlanRepository(PlanDatabase.getInstance(activity))
                val times = data.reminderTimes.map { time ->
                    require(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(time)) { "提醒时间无效" }
                    val (hour, minute) = time.split(":").map { it.toInt() }
                    ReminderEntity(planId = 0, hour = hour, minute = minute)
                }.distinctBy { it.hour to it.minute }
                require(times.isNotEmpty()) { "至少需要一个提醒时间" }
                PlanActions(repo, AlarmScheduler(activity)).save(
                    PlanEntity(name = data.name.trim(), category = data.category,
                        repeatMode = data.repeatMode, customDays = data.customDays.sorted().joinToString(",")), times)
            }
            _ui.update { it.copy(saved = true) }
            Toast.makeText(activity, "计划已保存，提醒已设置", Toast.LENGTH_SHORT).show()
            finishSpeaking("计划已保存，提醒已设置。")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("VoiceRunner", "Plan creation failed", e)
            val message = if (e is com.example.myplan.notification.AlarmSyncException)
                "计划已保存，提醒设置需要检查系统权限。" else "创建未完成，请检查提醒权限后重试。"
            _ui.update { it.copy(saved = e is com.example.myplan.notification.AlarmSyncException) }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            finishSpeaking(message)
        }
    }

    private fun speakAndListen(text: String) {
        _ui.update { it.copy(reply = text) }
        _phase.value = VoicePhase.SPEAKING
        val session = scope
        CloudTtsService.speak(text,
            done = { session.launch { delay(300); if (active) {
                if (_ui.value.pendingPlan != null) _phase.value = VoicePhase.WAITING
                else { _phase.value = VoicePhase.LISTENING; recorder?.startListening() }
            } } },
            error = { session.launch {
                if (_ui.value.pendingPlan != null) _phase.value = VoicePhase.WAITING else stop()
            } })
    }

    private fun finishSpeaking(text: String) {
        _ui.update { it.copy(reply = text, pendingPlan = null, isSaving = false) }
        _phase.value = VoicePhase.SPEAKING
        val session = scope
        CloudTtsService.speak(text, done = { session.launch { stop() } }, error = { session.launch { stop() } })
    }
}
