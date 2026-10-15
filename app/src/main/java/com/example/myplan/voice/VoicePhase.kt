package com.example.myplan.voice

/**
 * 胶囊 UI 显示的语音助手阶段。
 * VoiceRunner 持有 StateFlow<VoicePhase>，Compose 订阅自动刷新。
 */
enum class VoicePhase(val label: String) {
    IDLE("点击开始语音"),
    LISTENING("正在听，点击停止"),
    PROCESSING("少爷稍等..."),
    SPEAKING("回复中..."),
    WAITING("请说话")
}
