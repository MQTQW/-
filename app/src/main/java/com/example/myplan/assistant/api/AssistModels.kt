package com.example.myplan.assistant.api

import java.time.LocalDate
import java.time.LocalTime

enum class AssistRepeatMode { ONCE, EVERY_DAY, WEEKDAYS, CUSTOM }

data class AssistDraft(
    val title: String,
    val category: String,
    val repeatMode: AssistRepeatMode,
    val date: LocalDate?,
    val reminderTimes: List<LocalTime>,
    val customDays: List<Int>,
    val focusMinutes: Int?
)

data class AssistParseResult(val draft: AssistDraft?, val question: String?)
data class AssistStepSuggestion(val title: String, val focusMinutes: Int)

enum class AssistError {
    NOT_CONFIGURED, INVALID_CONFIGURATION, INVALID_INPUT, NETWORK, TIMEOUT,
    UNAUTHORIZED, RATE_LIMITED, SERVICE_UNAVAILABLE, INVALID_RESPONSE
}

class AssistException(val reason: AssistError, val userMessage: String) : Exception(userMessage)
