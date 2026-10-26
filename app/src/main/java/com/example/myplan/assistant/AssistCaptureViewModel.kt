package com.example.myplan.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.myplan.assistant.api.*
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.*
import com.example.myplan.voice.PlanVoiceData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class AssistCaptureState(
    val input: String = "", val reply: String = "", val question: String? = null, val error: String? = null,
    val busy: Boolean = false, val saving: Boolean = false, val preview: PlanVoiceData? = null,
    val savedPlanId: Long? = null, val usedAi: Boolean = false, val serviceConfigured: Boolean = false
)

class AssistCaptureViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val configuration = AssistConfiguration(application)
    private val service = AssistService(configuration::load)
    private val workflow = PlanDraftWorkflow { text, now -> service.parse(text, now) }
    private val _state = MutableStateFlow(AssistCaptureState(
        input = savedState["input"] ?: "", reply = savedState["reply"] ?: "", question = savedState["question"],
        preview = savedState.get<String>("preview")?.let { runCatching { PlanDraftCodec.decode(it) }.getOrNull() },
        usedAi = savedState["used_ai"] ?: false, serviceConfigured = configuration.load().isConfigured))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var token: String = savedState["token"] ?: UUID.randomUUID().toString().also { savedState["token"] = it }

    fun refreshConfiguration() { _state.update { it.copy(serviceConfigured = configuration.load().isConfigured) } }

    fun cancelAnalysis() {
        if (_state.value.saving) return
        job?.cancel(); revision++
        _state.update { it.copy(busy = false) }
    }

    fun updateInput(text: String) {
        if (_state.value.saving) return
        job?.cancel(); revision++
        savedState["input"] = text.take(500); savedState["reply"] = ""; savedState["question"] = null; savedState["preview"] = null
        _state.update { it.copy(input = text.take(500), reply = "", question = null, preview = null, busy = false, error = null) }
    }

    fun updateReply(text: String) {
        if (_state.value.saving) return
        job?.cancel(); revision++
        savedState["reply"] = text.take(200)
        _state.update { it.copy(reply = text.take(200), busy = false, error = null) }
    }

    fun acceptVoice(text: String) { if (_state.value.question != null) updateReply(text) else updateInput(text) }
    fun voiceError(message: String) { _state.update { it.copy(error = message) } }

    fun analyze() {
        val before = _state.value
        if (before.busy || before.saving) return
        val text = if (before.question != null && before.reply.isNotBlank()) before.input + "，" + before.reply else before.input
        if (text.isBlank()) { voiceError("先写下想做的事吧"); return }
        if (text.length > 500) { voiceError("内容较长，请缩短描述后再整理。"); return }
        val generation = ++revision
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                val result = workflow.parse(text, allowAi = configuration.load().isConfigured)
                ensureActive()
                if (generation != revision) return@launch
                val preview = result.plan?.copy(creationToken = token)
                savedState["input"] = text.take(500); savedState["reply"] = ""
                savedState["question"] = result.question; savedState["preview"] = preview?.let(PlanDraftCodec::encode)
                savedState["used_ai"] = result.usedAi
                _state.update { it.copy(input = text.take(500), reply = "", question = result.question, preview = preview,
                    usedAi = result.usedAi, busy = false, serviceConfigured = configuration.load().isConfigured) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == revision) _state.update { it.copy(busy = false, error = when (e) {
                    is AssistException -> e.userMessage
                    is IllegalArgumentException -> e.message
                    else -> "这次未能整理，请重试或手动创建。"
                }) }
            }
        }
    }

    fun confirm() {
        val plan = _state.value.preview ?: return
        if (_state.value.busy || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        job = viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    PlanDraftWorkflow.validate(plan)
                    val reminders = plan.reminderTimes.map { time ->
                        val parts = time.split(':').map(String::toInt)
                        ReminderEntity(planId = 0, hour = parts[0], minute = parts[1])
                    }
                    PlanActions(PlanRepository(PlanDatabase.getInstance(getApplication())), AlarmScheduler(getApplication())).save(
                        PlanEntity(name = plan.name.trim(), category = plan.category, repeatMode = plan.repeatMode,
                            customDays = plan.customDays.sorted().joinToString(","), oneTimeAt = plan.oneTimeAt,
                            suggestedFocusMinutes = plan.focusMinutes ?: 25, creationToken = plan.creationToken ?: token), reminders)
                }
                _state.update { it.copy(saving = false, savedPlanId = id) }
            } catch (e: CancellationException) { throw e }
            catch (e: AlarmSyncException) { _state.update { it.copy(saving = false, savedPlanId = e.planId, error = e.message) } }
            catch (e: Exception) { _state.update { it.copy(saving = false, error = e.message ?: "保存未完成，请重试。") } }
        }
    }

    fun reset() {
        if (_state.value.saving) return
        job?.cancel(); revision++
        savedState.keys().toList().forEach { savedState.remove<Any>(it) }
        token = UUID.randomUUID().toString(); savedState["token"] = token
        _state.value = AssistCaptureState(serviceConfigured = configuration.load().isConfigured)
    }
}
