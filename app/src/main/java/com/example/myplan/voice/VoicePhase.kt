package com.example.myplan.voice

/**
 * 胶囊 UI 显示的语音助手阶段。
 * VoiceRunner 持有 StateFlow<VoicePhase>，Compose 订阅自动刷新。
 */
enum class VoicePhase(val label: String) {
    IDLE("说一句话，轻轻记下来"),
    LISTENING("我在听，说说你的计划吧"),
    PROCESSING("正在整理你的计划…"),
    SPEAKING("听听这样安排是否合适"),
    WAITING("这样安排，可以吗？")
}
