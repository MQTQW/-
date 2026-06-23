package com.example.myplan.data

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS = "myplan_settings"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFirstLaunch(context: Context): Boolean =
        getPrefs(context).getBoolean("first_launch", true)

    fun setFirstLaunchDone(context: Context) {
        getPrefs(context).edit().putBoolean("first_launch", false).apply()
    }

    fun getNotificationRingtone(context: Context): String =
        getPrefs(context).getString("ringtone", "default") ?: "default"

    fun setNotificationRingtone(context: Context, value: String) {
        getPrefs(context).edit().putString("ringtone", value).apply()
    }

    fun getVibrationMode(context: Context): String =
        getPrefs(context).getString("vibration", "long") ?: "long"

    fun setVibrationMode(context: Context, value: String) {
        getPrefs(context).edit().putString("vibration", value).apply()
    }

    fun isDarkMode(context: Context): String =
        getPrefs(context).getString("dark_mode", "system") ?: "system"

    fun setDarkMode(context: Context, value: String) {
        getPrefs(context).edit().putString("dark_mode", value).apply()
    }

    fun getReminderMode(context: Context): String =
        getPrefs(context).getString("reminder_mode", "alarm") ?: "alarm"

    fun setReminderMode(context: Context, value: String) {
        getPrefs(context).edit().putString("reminder_mode", value).apply()
    }

    fun getAlarmRingtone(context: Context): String =
        getPrefs(context).getString("alarm_ringtone", "default") ?: "default"

    fun setAlarmRingtone(context: Context, value: String) {
        getPrefs(context).edit().putString("alarm_ringtone", value).apply()
    }
}
