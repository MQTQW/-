package com.example.myplan.focus.util

object FocusConstants {
    // Default monitoring interval in seconds
    const val DEFAULT_CHECK_INTERVAL = 30L

    // Warning thresholds (in minutes)
    const val WARNING_15_MIN = 15
    const val WARNING_5_MIN = 5
    const val WARNING_1_MIN = 1

    // Notification IDs (offset from FocusFlow to avoid collision with MY PLAN)
    const val NOTIF_MONITOR_ID = 3001
    const val NOTIF_WARNING_ID_BASE = 4000

    // SharedPreferences keys
    const val KEY_MONITORING_ENABLED = "focus_monitoring_enabled"
    const val KEY_CHECK_INTERVAL = "focus_check_interval"
    const val KEY_RESET_HOUR = "focus_reset_hour"
    const val KEY_SETUP_COMPLETE = "focus_setup_complete"

    // Default category names
    val DEFAULT_CATEGORIES = mapOf(
        "video" to "视频",
        "social" to "社交",
        "game" to "游戏",
        "shopping" to "购物",
        "other" to "其他"
    )

    // Intent actions
    const val ACTION_USAGE_UPDATED = "com.example.myplan.ACTION_USAGE_UPDATED"
    const val ACTION_DAILY_RESET = "com.example.myplan.ACTION_DAILY_RESET"

    // Plan category → app categories to block during focus
    val PLAN_TO_APP_CATEGORY_MAP: Map<String, List<String>> = mapOf(
        "学习" to listOf("social", "video", "game"),
        "工作" to listOf("social", "video"),
        "健康" to listOf("video"),
        "日常" to listOf("social")
    )

    fun getPlanCategoriesForAppCategory(appCat: String): List<String> {
        return PLAN_TO_APP_CATEGORY_MAP
            .filter { (_, blocked) -> appCat in blocked }
            .keys.toList()
    }
}
