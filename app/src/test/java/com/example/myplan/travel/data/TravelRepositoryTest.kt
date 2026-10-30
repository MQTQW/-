package com.example.myplan.travel.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TravelRepositoryTest {
    private lateinit var db: TravelDatabase
    private lateinit var repo: TravelRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TravelDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TravelRepository(db)
    }

    @After fun close() { db.close() }

    private fun trip(days: Int = 2) = TripEntity(title = "杭州两日", destination = "杭州", startDate = "2026-11-07",
        dayCount = days, timeZoneId = "Asia/Shanghai")

    private fun stop(title: String, day: Int = 0, position: Int = 0, minute: Int = 540) =
        TripStopDraft(dayIndex = day, position = position, title = title, startMinute = minute)

    private fun TripStopEntity.draft() = TripStopDraft(id, dayIndex, position, title, location, startMinute, durationMinutes, notes, isCompleted)

    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Invalid itinerary mutation must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun tripSaveNormalizesOrderAndKeepsStableCompletionWhileEditing() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("第二天", 1, 9), stop("上午", 0, 7), stop("下午", 0, 12)))
        val first = repo.observeTrip(id).first()!!
        assertEquals(listOf("上午", "下午", "第二天"), first.stops.map { it.title })
        assertEquals(listOf(0, 1, 0), first.stops.map { it.position })
        val completedId = first.stops.first().id
        repo.setCompleted(id, completedId, true)
        val old = repo.getTrip(id)!!
        repo.saveTrip(old.trip.copy(title = "调整后的杭州行程"), old.stops.reversed().map {
            it.draft().copy(position = 0, isCompleted = false)
        })
        val saved = repo.getTrip(id)!!
        assertEquals(old.stops.map { it.id }.toSet(), saved.stops.map { it.id }.toSet())
        assertTrue(saved.stops.single { it.id == completedId }.isCompleted)
        assertEquals(first.trip.createdAt, saved.trip.createdAt)
        assertTrue(saved.trip.updatedAt > old.trip.updatedAt)
        assertEquals(id, repo.observeTrips().first().single().trip.id)
    }

    @Test fun singleStopEditMovesAcrossDaysAndRetainsCompletion() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("A"), stop("B", position = 1), stop("C", position = 2)))
        val b = repo.getTrip(id)!!.stops[1]
        repo.setCompleted(id, b.id, true)
        val movedId = repo.saveStop(id, b.draft().copy(dayIndex = 1, position = 0, location = "西湖", isCompleted = false))
        assertEquals(b.id, movedId)
        val saved = repo.getTrip(id)!!.stops
        assertEquals(listOf("A", "C", "B"), saved.map { it.title })
        assertEquals(listOf(0, 1, 0), saved.map { it.position })
        assertTrue(saved.last().isCompleted)
        val addedId = repo.saveStop(id, stop("中间插入", position = 1))
        assertTrue(addedId > 0)
        assertEquals(listOf("A", "中间插入", "C", "B"), repo.getTrip(id)!!.stops.map { it.title })
    }

    @Test fun movingAndDeletingKeepContiguousPositionsWithoutChangingOtherDays() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("A"), stop("B", position = 1), stop("C", position = 2), stop("次日", day = 1)))
        val original = repo.getTrip(id)!!.stops
        repo.moveStop(id, original[2].id, -1)
        assertEquals(listOf("A", "C", "B", "次日"), repo.getTrip(id)!!.stops.map { it.title })
        repo.moveStop(id, original[0].id, -1)
        repo.deleteStop(id, original[2].id)
        val saved = repo.getTrip(id)!!.stops
        assertEquals(listOf("A", "B", "次日"), saved.map { it.title })
        assertEquals(listOf(0, 1, 0), saved.map { it.position })
        assertEquals(original.last(), saved.last())
    }

    @Test fun appendAddsAfterExistingNodesAndCapFailureDoesNotTruncate() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("保留节点")))
        repo.replaceStops(id, List(11) { stop("建议 $it", position = it) }, append = true)
        val before = repo.getTrip(id)!!
        assertEquals(12, before.stops.size)
        assertEquals("保留节点", before.stops.first().title)
        assertEquals((0..11).toList(), before.stops.map { it.position })
        invalid { repo.replaceStops(id, listOf(stop("第十三个")), append = true) }
        assertEquals(before, repo.getTrip(id))
    }

    @Test fun reducingTripDaysCannotSilentlyDeleteOutOfRangeNodes() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("第一天"), stop("第二天", day = 1)))
        val before = repo.getTrip(id)!!
        invalid { repo.saveTrip(before.trip.copy(dayCount = 1)) }
        assertEquals(before, repo.getTrip(id))
        repo.saveTrip(before.trip.copy(notes = "只改备注"))
        assertEquals(before.stops, repo.getTrip(id)!!.stops)
        repo.replaceStops(id, emptyList(), append = false)
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
    }

    @Test fun databaseWriteFailureRollsBackTripAndAllStopsIncludingCompletion() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("原有节点")))
        repo.setCompleted(id, repo.getTrip(id)!!.stops.single().id, true)
        val before = repo.getTrip(id)!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_stop BEFORE INSERT ON trip_stops
            WHEN NEW.title = '写入失败' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END""")
        try {
            repo.saveTrip(before.trip.copy(title = "不应保存"), listOf(before.stops.single().draft().copy(title = "写入失败")))
            fail("Expected injected database failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repo.getTrip(id))
    }

    @Test fun crossTripIdsAndDuplicateNodesCannotOverwriteAnotherItinerary() = runBlocking {
        val first = repo.saveTrip(trip(), listOf(stop("第一份")))
        val second = repo.saveTrip(trip().copy(title = "第二份"), listOf(stop("第二份")))
        val a = repo.getTrip(first)!!
        val b = repo.getTrip(second)!!
        val foreign = b.stops.single()
        invalid { repo.saveStop(first, foreign.draft()) }
        invalid { repo.deleteStop(first, foreign.id) }
        invalid { repo.setCompleted(first, foreign.id, true) }
        invalid { repo.replaceStops(first, listOf(foreign.draft()), append = false) }
        invalid { repo.replaceStops(first, listOf(a.stops.single().draft(), a.stops.single().draft()), append = false) }
        assertEquals(a, repo.getTrip(first))
        assertEquals(b, repo.getTrip(second))
    }

    @Test fun invalidFieldsAreRejectedWithoutCuttingOrPartiallySavingContent() = runBlocking {
        val id = repo.saveTrip(trip(), listOf(stop("原节点")))
        val before = repo.getTrip(id)!!
        listOf(
            stop("字".repeat(121)), stop("地点").copy(location = "字".repeat(201)),
            stop("备注").copy(notes = "字".repeat(1001)), stop("时间").copy(startMinute = 1440),
            stop("时长").copy(durationMinutes = 721), stop("日期", day = 2)
        ).forEach { draft -> invalid { repo.saveStop(id, draft) } }
        invalid { repo.saveTrip(before.trip.copy(dayCount = 15)) }
        invalid { repo.saveTrip(before.trip.copy(startDate = "2026-02-30")) }
        invalid { repo.saveTrip(before.trip.copy(timeZoneId = "invalid/time-zone")) }
        assertEquals(before, repo.getTrip(id))
    }

    @Test fun deletingTripCascadesItsStopsAndDoesNotDeleteOtherTrips() = runBlocking {
        val first = repo.saveTrip(trip(), listOf(stop("删除")))
        val second = repo.saveTrip(trip(), listOf(stop("保留")))
        val deleted = repo.getTrip(first)!!
        val other = repo.getTrip(second)
        repo.deleteTrip(first)
        assertNull(repo.observeTrip(first).first())
        assertTrue(db.travelDao().getStops(first).isEmpty())
        invalid { repo.saveTrip(deleted.trip, deleted.stops.map { it.draft() }) }
        assertNull(repo.getTrip(first))
        assertEquals(other, repo.getTrip(second))
    }

    @Test fun fileDatabaseReopenRetainsItineraryOrderAndCompletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "travel-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, TravelDatabase::class.java, name).allowMainThreadQueries().build()
        val first = open()
        val id = TravelRepository(first).saveTrip(trip(), listOf(stop("持久化", day = 1)))
        val stopId = TravelRepository(first).getTrip(id)!!.stops.single().id
        TravelRepository(first).setCompleted(id, stopId, true)
        val expected = TravelRepository(first).getTrip(id)
        first.close()
        val second = open()
        try {
            assertEquals(expected, TravelRepository(second).getTrip(id))
            val names = second.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertFalse("plans" in names)
            assertFalse("credit_entries" in names)
        } finally { second.close(); context.deleteDatabase(name) }
    }
}
