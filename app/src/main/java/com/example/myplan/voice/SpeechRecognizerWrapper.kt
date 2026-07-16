package com.example.myplan.voice

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * 语音输入封装。
 * 使用 Intent 拉起系统语音识别对话框（兼容没有 Google 语音引擎的国行 OPPO 等设备）。
 */
class SpeechRecognizerWrapper {

    private var listener: SpeechListener? = null

    interface SpeechListener {
        fun onWakeWordDetected()          // 检测到"新建计划"
        fun onFinalResult(text: String)   // 识别结果
        fun onError(message: String)
    }

    fun setListener(l: SpeechListener) { listener = l }

    /**
     * 创建语音识别 Intent。由 Activity 调用 startActivityForResult。
     */
    fun createIntent(useFreeForm: Boolean = true): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                if (useFreeForm) RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                else RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (useFreeForm) "说出你的计划" else "说\"新建计划\"")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    /**
     * 从 onActivityResult 的数据中提取文本。
     * @return 识别到的文字，可能为 null
     */
    fun extractResult(data: Intent?): String? {
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        return results?.getOrNull(0)
    }

    /**
     * 检查文本是否包含激活词。
     */
    fun containsWakeWord(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        return t.contains("新建计划") ||
            (t.contains("创建") && t.contains("计划")) ||
            t.contains("加个计划") ||
            t.contains("新建一个计划")
    }

    fun destroy() {}
}
