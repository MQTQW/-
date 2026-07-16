package com.example.myplan.voice

/**
 * 胶囊 UI 显示的语音助手阶段。
 * VoiceRunner 持有 StateFlow<VoicePhase>，Compose 订阅自动刷新。
 */
enum class VoicePhase(val label: String) {
    IDLE("说创建计划开始"),
    LISTENING("正在听..."),
    PROCESSING("少爷稍等..."),
    SPEAKING("回复中..."),
    WAITING("请说话")
}
