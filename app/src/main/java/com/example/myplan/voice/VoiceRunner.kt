package com.example.myplan.voice

import android.app.Activity
import android.util.Log
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

class VoiceRunner(private val activity: Activity) {
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: AudioRecorder? = null
    private var active = false
    private var conversation = DeepSeekConversation()
    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase

    fun start() {
        if (recorder != null) return
        CloudSttService.init(BuildConfig.VOLC_API_KEY)
        CloudTtsService.init(BuildConfig.VOLC_API_KEY)
        recorder = AudioRecorder(activity).apply {
            setListener(object : AudioRecorder.RecordListener {
                override fun onSpeechStarted() { if (active) _phase.value = VoicePhase.LISTENING }
                override fun onSpeechEnded(pcmData: ByteArray) {
                    if (!active) return
                    stopListening()
                    scope.launch { processAudio(pcmData) }
                }
                override fun onSilenceTimeout() { scope.launch { stop() } }
                override fun onError(msg: String) {
                    Log.e("VoiceRunner", msg)
                    scope.launch { stop() }
                }
            })
        }
    }

    /** Called only after the user taps the existing microphone capsule. Tapping again cancels. */
    fun startListening() {
        if (active) { stop(); return }
        if (BuildConfig.VOLC_API_KEY.isBlank() || BuildConfig.DEEPSEEK_KEY.isBlank()) {
            android.widget.Toast.makeText(activity, "语音服务尚未配置，请先填写构建密钥", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        start()
        if (recorder?.isPermissionGranted() != true) return
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        conversation = DeepSeekConversation().apply { init(BuildConfig.DEEPSEEK_KEY) }
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
    }

    private suspend fun processAudio(pcmData: ByteArray) {
        _phase.value = VoicePhase.PROCESSING
        val text = CloudSttService.recognize(pcmData)?.text?.trim()
        currentCoroutineContext().ensureActive()
        if (text.isNullOrBlank()) { speakAndListen("没听清，请再说一次。"); return }
        if (text in setOf("取消", "算了", "退出", "停止")) { stop(); return }
        val result = conversation.processUserInput(text)
        currentCoroutineContext().ensureActive()
        if (result == null) { speakAndListen("网络不太好，请再试一次。"); return }
        if (result.isCancel) { finishSpeaking(result.reply); return }
        if (result.isDone && result.planData != null) {
            val data = result.planData
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
                finishSpeaking("计划已保存，提醒已设置。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("VoiceRunner", "Plan creation failed", e)
                finishSpeaking(if (e is com.example.myplan.notification.AlarmSyncException)
                    "计划已保存，但提醒设置失败，请检查系统权限。" else "创建未完成，请检查提醒权限后重试。")
            }
            return
        }
        speakAndListen(result.reply)
    }

    private fun speakAndListen(text: String) {
        _phase.value = VoicePhase.SPEAKING
        val session = scope
        CloudTtsService.speak(text,
            done = { session.launch { delay(300); if (active) { _phase.value = VoicePhase.LISTENING; recorder?.startListening() } } },
            error = { session.launch { stop() } })
    }

    private fun finishSpeaking(text: String) {
        _phase.value = VoicePhase.SPEAKING
        val session = scope
        CloudTtsService.speak(text, done = { session.launch { stop() } }, error = { session.launch { stop() } })
    }
}
