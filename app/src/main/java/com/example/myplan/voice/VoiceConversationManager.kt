package com.example.myplan.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音对话状态机。
 * 休眠(仅检测激活词) → 激活(半双工对话) → 确认 → 创建 → 回到休眠。
 *
 * 参考：ai-assistant-android 的 VoiceStateMachine + iris 的 ConversationMemory
 */
sealed class ConversationState {
    /** 休眠：仅监听激活词"新建计划"，不上传语音 */
    object Dormant : ConversationState()

    /** 正在听用户说话（SpeechRecognizer 已激活） */
    object Listening : ConversationState()

    /** 处理中（NLU 或 API 调用中），暂停收音 */
    object Processing : ConversationState()

    /** 助手正在说话（TTS 播放中），暂停收音 */
    object Speaking : ConversationState()

    /** 追问缺失槽位 */
    data class Asking(
        val missingSlot: Slot,
        val filledSlots: Map<Slot, String>,
        val askCount: Int = 0
    ) : ConversationState()

    /** 所有槽位已填满，等待用户确认 */
    data class Confirming(val planData: PlanVoiceData) : ConversationState()

    /** 创建成功，短暂展示后自动回到 Dormant */
    data class Success(val planName: String) : ConversationState()

    /** 用户取消或超时退出 */
    object Cancelled : ConversationState()
}

/** 需要填充的槽位 */
enum class Slot(val description: String) {
    PLAN_NAME("想创建什么计划？"),
    REMINDER_TIMES("几点提醒你？"),
    REPEAT_MODE("工作日还是每天？")
}

/** 语音创建的完整计划数据 */
data class PlanVoiceData(
    var name: String = "",
    var reminderTimes: List<String> = emptyList(), // "08:00", "12:00"
    var repeatMode: String = "EVERY_DAY",          // EVERY_DAY / WEEKDAYS / CUSTOM
    var customDays: Set<Int> = emptySet(),          // 1=Mon..7=Sun
    var category: String = "日常"
)

/**
 * 对话状态管理器。持有当前状态和各槽位值。
 */
class VoiceConversationManager {

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Dormant)
    val state: StateFlow<ConversationState> = _state

    private var planData = PlanVoiceData()
    private var lastUserText: String = ""
    private var silenceStartMs: Long = 0L
    private var sameSlotAskCount: Slot? = null
    private var askCounter: Int = 0

    // ---- State Transitions ----

    fun onWakeWordDetected() {
        if (_state.value is ConversationState.Dormant) {
            _state.value = ConversationState.Listening
        }
    }

    fun onUserSpeechStarted() {
        if (_state.value !is ConversationState.Speaking) {
            _state.value = ConversationState.Listening
        }
    }

    fun onUserSpeechEnded(text: String) {
        lastUserText = text
        _state.value = ConversationState.Processing
    }

    fun onProcessingComplete(slots: SlotResult) {
        // Merge slots into planData and persist
        planData = mergeSlots(planData, slots)
        val merged = planData

        // Check for cancel intent
        if (slots.isCancel) {
            _state.value = ConversationState.Cancelled
            return
        }

        // Find first missing required slot
        val missing = findMissingSlot(merged)
        if (missing != null) {
            // Track same-slot re-asking
            if (missing == sameSlotAskCount) {
                askCounter++
            } else {
                sameSlotAskCount = missing
                askCounter = 1
            }
            _state.value = ConversationState.Asking(
                missingSlot = missing,
                filledSlots = toFilledMap(merged),
                askCount = askCounter
            )
        } else {
            // All slots filled → confirm
            _state.value = ConversationState.Confirming(merged)
        }
    }

    fun onConfirmResponse(confirmed: Boolean) {
        if (confirmed) {
            _state.value = ConversationState.Success(planData.name)
        } else {
            // Go back to listening for corrections
            _state.value = ConversationState.Listening
        }
    }

    fun onTtsStarted() {
        _state.value = ConversationState.Speaking
    }

    fun onTtsCompleted() {
        when (val s = _state.value) {
            is ConversationState.Confirming -> { /* wait for user response */ }
            is ConversationState.Success -> {
                _state.value = ConversationState.Dormant
            }
            is ConversationState.Cancelled -> {
                _state.value = ConversationState.Dormant
            }
            else -> _state.value = ConversationState.Listening
        }
    }

    fun onSilenceDetected(durationMs: Long) {
        if (durationMs >= 15_000L && _state.value !is ConversationState.Dormant) {
            _state.value = ConversationState.Cancelled
        }
    }

    fun onCancel() {
        _state.value = ConversationState.Cancelled
    }

    fun reset() {
        _state.value = ConversationState.Dormant
        planData.copy(
            name = "", reminderTimes = emptyList(),
            repeatMode = "EVERY_DAY", customDays = emptySet(), category = "日常"
        )
    }

    fun getPlanData(): PlanVoiceData = planData.copy()

    // ---- Internal helpers ----

    private fun mergeSlots(current: PlanVoiceData, result: SlotResult): PlanVoiceData {
        return current.copy(
            name = result.planName.takeIf { !it.isNullOrBlank() } ?: current.name,
            reminderTimes = result.reminderTimes.takeIf { !it.isNullOrEmpty() } ?: current.reminderTimes,
            repeatMode = result.repeatMode ?: current.repeatMode,
            customDays = result.customDays ?: current.customDays,
            category = result.category ?: current.category
        )
    }

    private fun findMissingSlot(data: PlanVoiceData): Slot? {
        if (data.name.isBlank()) return Slot.PLAN_NAME
        if (data.reminderTimes.isEmpty()) return Slot.REMINDER_TIMES
        // REPEAT_MODE always defaults to EVERY_DAY, so technically never "missing"
        // But we still ask to confirm
        if (data.repeatMode == "EVERY_DAY" && data.customDays.isEmpty()) {
            return Slot.REPEAT_MODE
        }
        return null
    }

    private fun toFilledMap(data: PlanVoiceData): Map<Slot, String> = buildMap {
        if (data.name.isNotBlank()) put(Slot.PLAN_NAME, data.name)
        if (data.reminderTimes.isNotEmpty()) {
            put(Slot.REMINDER_TIMES, data.reminderTimes.joinToString(" · "))
        }
        val repeatText = when (data.repeatMode) {
            "WEEKDAYS" -> "工作日"
            "CUSTOM" -> "自定义(${data.customDays.joinToString(",")})"
            else -> "每天"
        }
        put(Slot.REPEAT_MODE, repeatText)
    }
}
