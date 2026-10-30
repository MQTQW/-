package com.example.myplan.travel.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class TripScheduleTest {
    private fun trip(date: String = "2026-11-01", zone: String = "Asia/Tokyo") =
        TripEntity(id = 1, title = "旅行", destination = "目的地", startDate = date, dayCount = 2, timeZoneId = zone)

    private fun stop(id: Long, day: Int, minute: Int, duration: Int, title: String = "节点 $id") =
        TripStopEntity(id = id, tripId = 1, dayIndex = day, position = id.toInt(), title = title, startMinute = minute, durationMinutes = duration)

    @Test fun tripTimeZoneRemainsStableWhenPhoneTimeZoneChanges() {
        val original = TimeZone.getDefault()
        try {
            val trip = trip()
            val stop = stop(1, 1, 9 * 60 + 30, 60)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val first = TripSchedule.resolveInstant(trip, stop)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(first, TripSchedule.resolveInstant(trip, stop))
            assertEquals(Instant.parse("2026-11-02T00:30:00Z"), first)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun daylightSavingGapAndRepeatedHourAreRejectedRatherThanShifted() {
        for ((date, minute) in listOf("2026-03-08" to 150, "2026-11-01" to 90)) {
            try {
                TripSchedule.resolveInstant(trip(date, "America/New_York"), stop(1, 0, minute, 60))
                fail("Ambiguous or nonexistent local time must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"),
            TripSchedule.resolveInstant(trip("2026-03-08", "America/New_York"), stop(1, 0, 180, 60)))
    }

    @Test fun crossDayOverlapIsReportedButTouchingIntervalsAreAllowed() {
        val late = stop(1, 0, 23 * 60, 120, "夜间转场")
        val early = stop(2, 1, 30, 30, "次日早餐")
        val adjacent = stop(3, 1, 60, 30, "次日出发")
        val conflicts = TripSchedule.conflicts(listOf(adjacent, early, late))
        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().contains("夜间转场") && conflicts.single().contains("次日早餐"))
        assertTrue(TripSchedule.conflicts(listOf(late, adjacent)).isEmpty())
    }

    @Test fun conflictDetectionFindsContainmentRegardlessOfDisplayOrder() {
        val long = stop(1, 0, 480, 180)
        val inside = stop(2, 0, 510, 30)
        val later = stop(3, 0, 600, 90)
        assertEquals(2, TripSchedule.conflicts(listOf(later, inside, long)).size)
    }
}
