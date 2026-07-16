package com.example.myplan.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android TextToSpeech 封装。
 * 添加 UtteranceProgressListener（iris 没加导致检测不到 TTS 结束）。
 *
 * 参考：ai-assistant 的 NativeTtsManager + iris 的 IrisTts
 */
class TextToSpeechWrapper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private var onDoneCallback: (() -> Unit)? = null
    private var pendingText: String? = null

    interface TtsListener {
        fun onTtsReady()
        fun onTtsDone()
        fun onTtsError(msg: String)
    }

    private var listener: TtsListener? = null
    fun setListener(l: TtsListener) { listener = l }

    fun isAvailable(): Boolean = isInitialized

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    listener?.onTtsError("中文TTS不可用，请下载中文语音包")
                    return@TextToSpeech
                }
                isInitialized = true
                listener?.onTtsReady()

                // Set up completion listener (critical — iris didn't have this)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onDoneCallback?.invoke()
                        listener?.onTtsDone()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        listener?.onTtsError("TTS播放失败")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        listener?.onTtsError("TTS播放失败(code=$errorCode)")
                    }
                })

                // Play any pending text
                pendingText?.let { speak(it) }
                pendingText = null
            } else {
                listener?.onTtsError("TTS初始化失败(status=$status)")
            }
        }
    }

    /**
     * Speak text. If TTS not yet initialized, queue it.
     */
    fun speak(text: String) {
        if (!isInitialized) {
            pendingText = text
            return
        }
        // Use QUEUE_FLUSH to replace any current utterance (half-duplex)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myplan_tts_${System.currentTimeMillis()}")
    }

    /**
     * Stop current TTS immediately (for barge-in by user).
     */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * Get the TTS voice text for asking about a slot.
     * Randomly picks from 3 variants to reduce mechanical feel.
     */
    fun getAskText(slot: Slot, askCount: Int): String {
        return when (slot) {
            Slot.PLAN_NAME -> when (askCount % 3) {
                0 -> "想创建什么计划？"
                1 -> "具体是什么计划呢？"
                else -> "要提醒你做什么？比如吃药、学习。"
            }
            Slot.REMINDER_TIMES -> when (askCount % 3) {
                0 -> "几点提醒你？"
                1 -> "什么时间提醒你？可以设多个时间。"
                else -> "想在什么时候收到提醒？比如早上8点和晚上8点。"
            }
            Slot.REPEAT_MODE -> when (askCount % 3) {
                0 -> "工作日还是每天？"
                1 -> "是每天提醒，还是只在工作日？"
                else -> "每天都提醒，还是只在周一到周五？"
            }
        }
    }

    /**
     * Get confirmation text with all slot values.
     */
    fun getConfirmText(data: PlanVoiceData): String {
        val timesText = data.reminderTimes.joinToString("、")
        val repeatText = when (data.repeatMode) {
            "WEEKDAYS" -> "工作日"
            "CUSTOM" -> {
                val dayNames = data.customDays.map { d ->
                    when(d) {1->"周一" 2->"周二" 3->"周三" 4->"周四" 5->"周五" 6->"周六" 7->"周日" else->""}
                }.joinToString("")
                "每${dayNames}"
            }
            else -> "每天"
        }
        return "好的——「${data.name}」，${repeatText}${timesText}提醒你。确认创建吗？"
    }

    fun getSuccessText(name: String): String = "已创建「${name}」。有需要再叫我。"

    fun getWelcomeText(): String = "你好，想创建什么计划？"

    fun getGoodbyeText(): String = "有需要再叫我。"
}
