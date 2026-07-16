package com.example.myplan.focus.util

import java.util.concurrent.TimeUnit

object TimeFormatter {

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0分钟"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }

    fun formatShort(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatRemaining(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        return when {
            minutes > 0 -> "${minutes}分钟"
            else -> "${TimeUnit.MILLISECONDS.toSeconds(millis)}秒"
        }
    }

    fun toMillis(hours: Int, minutes: Int): Long {
        return TimeUnit.HOURS.toMillis(hours.toLong()) +
                TimeUnit.MINUTES.toMillis(minutes.toLong())
    }

    fun getHours(millis: Long): Int {
        return TimeUnit.MILLISECONDS.toHours(millis).toInt()
    }

    fun getMinutes(millis: Long): Int {
        return (TimeUnit.MILLISECONDS.toMinutes(millis) % 60).toInt()
    }
}
