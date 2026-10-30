package com.example.myplan.travel.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

class TravelRepository(private val db: TravelDatabase) {
    private val dao = db.travelDao()

    fun observeTrips(): Flow<List<TripWithStops>> = dao.observeTrips().map { rows -> rows.map(::ordered) }
    fun observeTrip(id: Long): Flow<TripWithStops?> = dao.observeTrip(id).map { it?.let(::ordered) }
    suspend fun getTrip(id: Long): TripWithStops? = dao.getTrip(id)?.let(::ordered)

    suspend fun saveTrip(trip: TripEntity, steps: List<TripStopDraft>? = null): Long = db.withTransaction {
        validateTrip(trip)
        val old = dao.getTripEntity(trip.id)
        require(trip.id == 0L || old != null) { "行程已删除，请返回后重新创建" }
        val existingStops = dao.getStops(trip.id)
        val prepared = if (steps == null) existingStops else prepareStops(trip, steps, existingStops)
        validateStops(trip, prepared)
        val saved = trip.copy(title = trip.title.trim(), destination = trip.destination.trim(),
            createdAt = old?.createdAt ?: trip.createdAt, updatedAt = revision(trip.updatedAt, old?.updatedAt))
        val id = if (old == null) dao.insertTrip(saved) else { dao.updateTrip(saved); saved.id }
        if (steps != null) writeStops(id, prepared)
        id
    }

    suspend fun saveStop(tripId: Long, draft: TripStopDraft): Long = db.withTransaction {
        val trip = requireTrip(tripId)
        require(draft.position >= 0) { "节点顺序无效" }
        val all = dao.getStops(tripId)
        val old = if (draft.id == 0L) null else {
            all.find { it.id == draft.id } ?: throw IllegalArgumentException("节点不存在或不属于此行程")
        }
        val stop = fromDraft(tripId, draft, old)
        val rest = all.filter { it.id != old?.id }
        val day = rest.filter { it.dayIndex == stop.dayIndex }.toMutableList()
        val position = draft.position.coerceAtMost(day.size)
        day.add(position, stop)
        val changed = rest.filter { it.dayIndex != stop.dayIndex } + day.mapIndexed { index, row -> row.copy(position = index) }
        validateStops(trip, changed)
        val saved = writeStops(tripId, changed)
        touch(trip)
        saved.single { it.dayIndex == stop.dayIndex && it.position == position }.id
    }

    suspend fun deleteStop(tripId: Long, stopId: Long) = db.withTransaction {
        val trip = requireTrip(tripId)
        val stops = dao.getStops(tripId)
        require(stops.any { it.id == stopId }) { "节点不存在或不属于此行程" }
        writeStops(tripId, stops.filter { it.id != stopId })
        touch(trip)
    }

    suspend fun moveStop(tripId: Long, stopId: Long, delta: Int) = db.withTransaction {
        val trip = requireTrip(tripId)
        val stops = dao.getStops(tripId)
        val stop = stops.find { it.id == stopId } ?: throw IllegalArgumentException("节点不存在或不属于此行程")
        val day = stops.filter { it.dayIndex == stop.dayIndex }.toMutableList()
        val from = day.indexOfFirst { it.id == stopId }
        val to = (from.toLong() + delta).coerceIn(0L, day.lastIndex.toLong()).toInt()
        if (from != to) {
            day.removeAt(from)
            day.add(to, stop)
            writeStops(tripId, stops.filter { it.dayIndex != stop.dayIndex } + day.mapIndexed { index, row -> row.copy(position = index) })
            touch(trip)
        }
    }

    suspend fun setCompleted(tripId: Long, stopId: Long, completed: Boolean) = db.withTransaction {
        val trip = requireTrip(tripId)
        val stop = dao.getStop(tripId, stopId) ?: throw IllegalArgumentException("节点不存在或不属于此行程")
        dao.updateStop(stop.copy(isCompleted = completed))
        touch(trip)
    }

    suspend fun deleteTrip(id: Long) = db.withTransaction { dao.deleteTrip(id) }

    suspend fun replaceStops(tripId: Long, stops: List<TripStopDraft>, append: Boolean) = db.withTransaction {
        val trip = requireTrip(tripId)
        val old = dao.getStops(tripId)
        if (append) require(stops.all { it.id == 0L }) { "追加时只能选择新节点" }
        val incoming = prepareStops(trip, stops, old)
        val combined = if (!append) incoming else {
            // Appending keeps the existing order even when suggestions start at position zero.
            val counters = old.groupingBy { it.dayIndex }.eachCount().toMutableMap()
            old + incoming.sortedWith(compareBy({ it.dayIndex }, { it.position })).map { stop ->
                val position = counters[stop.dayIndex] ?: 0
                counters[stop.dayIndex] = position + 1
                stop.copy(position = position)
            }
        }
        validateStops(trip, combined)
        writeStops(tripId, combined)
        touch(trip)
    }

    private suspend fun requireTrip(id: Long): TripEntity = dao.getTripEntity(id)
        ?: throw IllegalArgumentException("行程不存在")

    private fun ordered(row: TripWithStops): TripWithStops = row.copy(
        stops = row.stops.sortedWith(compareBy({ it.dayIndex }, { it.position }, { it.id })))

    private fun prepareStops(trip: TripEntity, drafts: List<TripStopDraft>, old: List<TripStopEntity>): List<TripStopEntity> {
        val ids = drafts.filter { it.id != 0L }.map { it.id }
        require(ids.distinct().size == ids.size) { "节点不能重复" }
        val existing = old.associateBy { it.id }
        require(ids.all { existing.containsKey(it) }) { "节点不存在或不属于此行程" }
        return drafts.map { fromDraft(trip.id, it, existing[it.id]) }
    }

    private fun fromDraft(tripId: Long, draft: TripStopDraft, old: TripStopEntity?) = TripStopEntity(
        id = draft.id, tripId = tripId, dayIndex = draft.dayIndex, position = draft.position,
        title = draft.title.trim(), location = draft.location.trim(), startMinute = draft.startMinute,
        durationMinutes = draft.durationMinutes, notes = draft.notes, isCompleted = old?.isCompleted ?: draft.isCompleted)

    private fun validateTrip(trip: TripEntity) {
        require(trip.title.trim().length in 1..120) { "行程名称需要 1 至 120 个字" }
        require(trip.destination.trim().length in 1..200) { "请填写 1 至 200 字的目的地" }
        require(trip.notes.length <= 1000) { "行程备注最多 1000 字" }
        require(trip.dayCount in 1..14) { "行程天数需要在 1 至 14 天之间" }
        require(trip.transport in setOf("公共交通", "步行", "自驾")) { "请选择有效的出行方式" }
        require(trip.pace in setOf("舒缓", "适中", "紧凑")) { "请选择有效的行程节奏" }
        try {
            require(LocalDate.parse(trip.startDate).toString() == trip.startDate) { "请选择有效的出发日期" }
            ZoneId.of(trip.timeZoneId)
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("行程日期或时区无效", e)
        }
    }

    private fun validateStops(trip: TripEntity, stops: List<TripStopEntity>) {
        require(stops.groupingBy { it.dayIndex }.eachCount().values.all { it <= 12 }) { "每天最多安排 12 个节点，请先调整再保存" }
        stops.forEach { stop ->
            require(stop.dayIndex in 0 until trip.dayCount && stop.position >= 0) { "节点日期或顺序无效，减少天数前请先调整节点" }
            require(stop.title.trim().length in 1..120) { "节点名称需要 1 至 120 个字" }
            require(stop.location.length <= 200) { "地点最多 200 字" }
            require(stop.notes.length <= 1000) { "节点备注最多 1000 字" }
            require(stop.startMinute in 0..1439 && stop.durationMinutes in 1..720) { "请设置有效的节点时间和 1 至 720 分钟的时长" }
            TripSchedule.resolveInstant(trip, stop)
        }
    }

    private suspend fun writeStops(tripId: Long, stops: List<TripStopEntity>): List<TripStopEntity> {
        dao.deleteStops(tripId)
        return stops.groupBy { it.dayIndex }.toSortedMap().flatMap { (_, day) ->
            day.sortedWith(compareBy({ it.position }, { it.id })).mapIndexed { index, row ->
                val normalized = row.copy(tripId = tripId, position = index)
                normalized.copy(id = dao.insertStop(normalized))
            }
        }
    }

    private fun revision(requested: Long, previous: Long?): Long =
        maxOf(requested, System.currentTimeMillis(), (previous ?: 0L) + 1)

    private suspend fun touch(trip: TripEntity) = dao.updateTrip(trip.copy(updatedAt = revision(trip.updatedAt, trip.updatedAt)))
}
