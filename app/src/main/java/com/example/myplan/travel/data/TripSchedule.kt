package com.example.myplan.travel.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object TripSchedule {
    /** A trip retains its chosen time zone even if the phone's time zone changes. */
    fun resolveInstant(trip: TripEntity, stop: TripStopEntity): Instant {
        require(stop.tripId == trip.id) { "节点不属于此行程" }
        require(stop.dayIndex in 0 until trip.dayCount && stop.startMinute in 0..1439) { "行程日期或时间无效" }
        try {
            val date = LocalDate.parse(trip.startDate).plusDays(stop.dayIndex.toLong())
            val local = date.atTime(LocalTime.of(stop.startMinute / 60, stop.startMinute % 60))
            val offsets = ZoneId.of(trip.timeZoneId).rules.getValidOffsets(local)
            require(offsets.size == 1) { "这个时间受夏令时影响而不存在或有歧义，请调整时间" }
            return local.toInstant(offsets.single())
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("行程日期或时区无效", e)
        }
    }

    /** Local itinerary intervals, including stops extending into the next day. */
    fun conflicts(stops: List<TripStopEntity>): List<String> {
        val sorted = stops.sortedWith(compareBy({ it.dayIndex }, { it.startMinute }, { it.position }, { it.id }))
        val result = mutableListOf<String>()
        sorted.forEachIndexed { index, first ->
            val start = first.dayIndex.toLong() * 1440 + first.startMinute
            val end = start + first.durationMinutes
            for (second in sorted.drop(index + 1)) {
                val nextStart = second.dayIndex.toLong() * 1440 + second.startMinute
                if (nextStart >= end) break
                result += "第 ${first.dayIndex + 1} 天「${first.title}」与第 ${second.dayIndex + 1} 天「${second.title}」时间重叠"
            }
        }
        return result
    }
}
