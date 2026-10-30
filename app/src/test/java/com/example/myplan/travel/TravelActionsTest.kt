package com.example.myplan.travel

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.example.myplan.travel.data.TripEntity
import com.example.myplan.travel.data.TripStopEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelActionsTest {
    private val trip = TripEntity(id = 3, title = "周末出行", destination = "测试城市", startDate = "2030-05-01",
        dayCount = 2, timeZoneId = "Asia/Tokyo")
    private val stop = TripStopEntity(id = 9, tripId = 3, dayIndex = 0, position = 0, title = "到达车站",
        location = "车站 A & B?", startMinute = 9 * 60, durationMinutes = 30)
    private val before = Instant.parse("2030-04-01T00:00:00Z")

    @Test fun reminderUsesTripZoneAndDisplaysPhoneTimeWithoutChangingInstant() {
        val draft = TravelActions.reminderDraft(trip, stop, before, ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2030-05-01T00:00:00Z").toEpochMilli(), draft.oneTimeAt)
        assertEquals(listOf("08:00"), draft.reminderTimes)
        assertEquals("ONCE", draft.repeatMode)
        assertEquals("行程 · 到达车站", draft.name)
    }

    @Test fun repeatedReminderDraftKeepsIdempotencyTokenButReschedulingChangesIt() {
        val first = TravelActions.reminderDraft(trip, stop, before)
        assertEquals(first.creationToken, TravelActions.reminderDraft(trip, stop, before).creationToken)
        assertNotEquals(first.creationToken, TravelActions.reminderDraft(trip, stop.copy(startMinute = 600), before).creationToken)
    }

    @Test fun pastAndCompletedStopsCannotCreateReminders() {
        assertThrows(IllegalArgumentException::class.java) {
            TravelActions.reminderDraft(trip, stop, Instant.parse("2030-05-01T00:00:00Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TravelActions.reminderDraft(trip, stop.copy(isCompleted = true), before)
        }
    }

    @Test fun mapQueryEncodesPlaceAsDataAndLeavesMapAppChoiceToUser() {
        val intent = TravelActions.mapIntent(trip, stop)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:0,0?q=" + Uri.encode("测试城市 车站 A & B?"), intent.dataString)
        assertNull(intent.`package`)
    }

    @Test fun mapUsesActivityTitleWhenLocationIsEmpty() {
        assertEquals("测试城市 到达车站", TravelActions.mapQuery(trip, stop.copy(location = "")))
    }
}
