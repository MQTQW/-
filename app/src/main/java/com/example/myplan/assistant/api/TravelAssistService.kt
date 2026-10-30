package com.example.myplan.assistant.api

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class TravelAssistRequest(
    val destination: String,
    val title: String,
    val startDate: String,
    val dayCount: Int,
    val timeZoneId: String,
    val transport: String = "公共交通",
    val pace: String = "适中",
    val notes: String = ""
)

data class TravelAssistStop(
    val title: String,
    val location: String,
    val startTime: String,
    val durationMinutes: Int,
    val notes: String = ""
)

data class TravelAssistDay(val day: Int, val stops: List<TravelAssistStop>)
data class TravelAssistResult(val days: List<TravelAssistDay>)

/** Generates an editable suggestion; it never saves trips or accesses maps/live travel data. */
class TravelAssistService(
    config: () -> AssistConfig,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(50, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) {
    private val http = AssistService(config, client)

    suspend fun generate(request: TravelAssistRequest): TravelAssistResult {
        TravelAssistJson.validateRequest(request)
        val body = JSONObject().put("operation", "itinerary")
            .put("destination", request.destination.trim()).put("title", request.title.trim())
            .put("startDate", request.startDate).put("dayCount", request.dayCount).put("timeZoneId", request.timeZoneId)
            .put("transport", request.transport).put("pace", request.pace).put("notes", request.notes.trim())
        return TravelAssistJson.parse(http.request(body, MAX_RESPONSE_BYTES), request)
    }

    companion object { internal const val MAX_RESPONSE_BYTES = 512L * 1024 }
}

internal object TravelAssistJson {
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

    fun validateRequest(request: TravelAssistRequest) {
        try {
            require(AssistJson.validInput(request.destination, 200) && AssistJson.validInput(request.title, 120))
            require(AssistJson.validInput(request.notes, 1000, allowEmpty = true) && request.dayCount in 1..14)
            require(datePattern.matches(request.startDate))
            val date = LocalDate.parse(request.startDate)
            require(date.year >= 1 && date.plusDays(request.dayCount - 1L).year <= 9999)
            require(request.timeZoneId.length <= 80 && request.timeZoneId in ZoneId.getAvailableZoneIds())
            require(request.transport in setOf("公共交通", "步行", "自驾") && request.pace in setOf("舒缓", "适中", "紧凑"))
        } catch (_: Exception) {
            throw AssistException(AssistError.INVALID_INPUT, "请检查目的地、旅行名称、出发日期、1～14 天天数、时区和出行偏好。")
        }
    }

    fun parse(text: String, request: TravelAssistRequest): TravelAssistResult = try {
        val root = JSONObject(text)
        root.requireKeys("days")
        val days = root.getJSONArray("days")
        require(days.length() == request.dayCount)
        TravelAssistResult(days.mapStrict { index, raw ->
            require(raw is JSONObject)
            raw.requireKeys("day", "stops")
            val day = raw.get("day")
            require(day is Int && day == index + 1)
            val stops = raw.getJSONArray("stops")
            require(stops.length() in 1..6)
            var previousEnd = 0
            TravelAssistDay(day, stops.mapStrict { _, stop ->
                require(stop is JSONObject)
                stop.requireKeys("title", "location", "startTime", "durationMinutes", "notes")
                val title = stop.text("title", 120)
                val location = stop.text("location", 200)
                val startTime = stop.text("startTime", 5)
                require(timePattern.matches(startTime))
                val duration = stop.get("durationMinutes")
                require(duration is Int && duration in 1..720)
                val time = LocalTime.parse(startTime)
                val start = time.hour * 60 + time.minute
                val end = start + duration
                require(start >= previousEnd && end <= 24 * 60)
                previousEnd = end
                TravelAssistStop(title, location, startTime, duration, stop.text("notes", 1000, allowEmpty = true))
            })
        })
    } catch (_: Exception) {
        throw AssistException(AssistError.INVALID_RESPONSE, "AI 返回的行程暂时无法使用，请重试或手动安排行程。")
    }

    private fun JSONObject.requireKeys(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet())
    }
    private fun JSONObject.text(key: String, maximum: Int, allowEmpty: Boolean = false): String {
        val value = get(key)
        require(value is String && AssistJson.validInput(value, maximum, allowEmpty))
        return value.trim()
    }
    private fun <T> JSONArray.mapStrict(transform: (Int, Any) -> T): List<T> =
        (0 until length()).map { index -> transform(index, get(index)) }
}
