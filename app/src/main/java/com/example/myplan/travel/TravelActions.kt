package com.example.myplan.travel

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.example.myplan.travel.data.TripEntity
import com.example.myplan.travel.data.TripSchedule
import com.example.myplan.travel.data.TripStopEntity
import com.example.myplan.voice.PlanVoiceData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A travel stop can open a normal reminder draft; it does not silently create or own a plan. */
object TravelActions {
    fun reminderDraft(trip: TripEntity, stop: TripStopEntity, now: Instant = Instant.now(),
        phoneZone: ZoneId = ZoneId.systemDefault()): PlanVoiceData {
        require(!stop.isCompleted) { "这个节点已完成，无需再创建提醒。" }
        val at = TripSchedule.resolveInstant(trip, stop)
        require(at > now) { "这个节点的时间已过，请先调整行程时间。" }
        return PlanVoiceData(name = "行程 · ${stop.title}", category = "日常", repeatMode = "ONCE",
            reminderTimes = listOf(at.atZone(phoneZone).format(DateTimeFormatter.ofPattern("HH:mm"))),
            oneTimeAt = at.toEpochMilli(), creationToken = "travel-stop-${stop.id}-${at.epochSecond}")
    }

    fun mapQuery(trip: TripEntity, stop: TripStopEntity): String =
        "${trip.destination.trim()} ${stop.location.ifBlank { stop.title }.trim()}".trim()

    fun mapIntent(trip: TripEntity, stop: TripStopEntity): Intent =
        Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(mapQuery(trip, stop))}".toUri())

    fun openMap(context: Context, trip: TripEntity, stop: TripStopEntity) {
        try {
            context.startActivity(mapIntent(trip, stop))
        } catch (_: ActivityNotFoundException) {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("行程地点", mapQuery(trip, stop)))
            Toast.makeText(context, "未找到地图应用，地点已复制。", Toast.LENGTH_LONG).show()
        }
    }
}
