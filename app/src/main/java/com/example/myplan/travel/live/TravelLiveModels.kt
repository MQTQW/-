package com.example.myplan.travel.live

import com.example.myplan.travel.data.TripEntity
import com.example.myplan.travel.data.TripStopDraft
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class TravelLiveException(val code: String, message: String) : Exception(message)
enum class TravelLivePhase { INPUT, CANDIDATES, WISHLIST, CONDITIONS, RESULT }
data class TravelLiveForm(val destination: String = "", val days: Int = 2, val date: String = "", val undated: Boolean = true,
    val arrival: String = "", val returnTime: String = "", val hotel: String = "", val pace: String = "舒缓", val mode: String = "公共交通") {
    fun json(): JSONObject = JSONObject().put("destination", destination).put("days", days).put("date", date).put("undated", undated)
        .put("arrival", arrival).put("returnTime", returnTime).put("hotel", hotel).put("pace", pace).put("mode", mode)
    fun validate() {
        require(destination.isNotBlank() && days in 1..5) { "请确认目的地和 1 至 5 天的行程天数。" }
        require(undated || validDate(date)) { "请选择有效的出发日期，或选择日期未定。" }
        require(listOf(arrival, returnTime).all { it.isEmpty() || Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) }) { "到达与返程时间请填写 HH:mm。" }
        require(hotel.length <= 200 && pace in setOf("舒缓", "适中", "紧凑") && mode in setOf("公共交通", "步行", "自驾")) { "请检查住宿、节奏与出行方式。" }
        if (!undated) require(LocalDate.parse(date).plusDays((days - 1).toLong()).year <= 9999) { "日期超出有效范围。" }
    }
}
data class TravelSource(val id: String, val title: String, val author: String, val url: String)
data class TravelCandidate(val id: String, val title: String, val category: String, val description: String, val duration: Int,
    val region: String, val locationStatus: String, val image: String?, val sourceIds: List<String>)
data class TravelResearch(val id: String, val destination: String, val candidates: List<TravelCandidate>, val sources: List<TravelSource>,
    val warnings: List<String>, val fetchedAt: String)
data class TravelUnplaced(val id: String, val title: String, val reason: String)
data class TravelLiveStop(val id: String, val selectionId: String?, val title: String, val time: String, val duration: Int,
    val notes: String, val locationStatus: String, val mustKeep: Boolean, val sourceIds: List<String>, val travelMinutes: Int?,
    val travelCheckedAt: String, val region: String, val sourceRefs: String, val verification: String)
data class TravelLiveDay(val day: Int, val date: String?, val stops: List<TravelLiveStop>)
data class TravelLiveTrip(val id: String, val title: String, val days: List<TravelLiveDay>, val sources: List<TravelSource>,
    val warnings: List<String>, val planningNote: String, val form: TravelLiveForm, val evidenceJson: String = "")
data class TravelConnection(val research: Boolean, val ai: Boolean, val map: Boolean, val loggedIn: Boolean, val message: String)
data class TravelLiveJob(val id: String, val kind: String, val status: String, val progress: String, val result: JSONObject?, val errorCode: String?)
data class TravelLiveState(val config: TravelLiveConfig = TravelLiveConfig(), val phase: TravelLivePhase = TravelLivePhase.INPUT,
    val destination: String = "", val research: TravelResearch? = null, val selected: Map<String, String> = emptyMap(),
    val form: TravelLiveForm = TravelLiveForm(), val trip: TravelLiveTrip? = null, val busy: Boolean = false,
    val progress: String = "", val error: String? = null, val unplaced: List<TravelUnplaced> = emptyList(),
    val connection: String = "尚未连接", val activeJobId: String? = null, val savedTripId: Long? = null, val saving: Boolean = false)

internal fun validDate(value: String) = try { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) && LocalDate.parse(value).year in 1..9999 } catch (_: Exception) { false }
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.strings(key: String) = optJSONArray(key)?.strings().orEmpty()
internal fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
internal fun JSONObject.integer(key: String): Int {
    val value = get(key)
    require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble())
    return value.toInt()
}

/** Parse only server-owned public fields. Full source bodies are not retained in native drafts. */
object TravelLiveJson {
    fun sources(value: JSONArray?): List<TravelSource> {
        require(value == null || value.length() <= 12)
        val sources = value?.objects().orEmpty().map {
        TravelSource(it.getString("id"), it.optString("title").take(300), it.optString("author").take(120), sourceUrl(it.optString("url")))
        }
        require(sources.map { it.id }.distinct().size == sources.size)
        return sources
    }
    fun sourceUrl(value: String): String = try {
        val uri = java.net.URI(value)
        val host = uri.host?.lowercase().orEmpty()
        value.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && (host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") || host == "xhslink.com" || host.endsWith(".xhslink.com")) }.orEmpty()
    } catch (_: Exception) { "" }
    fun imagePath(value: String?): String? = value?.takeIf { Regex("^/api/images/[A-Za-z0-9_-]{10,100}$").matches(it) }
    fun research(json: JSONObject, destination: String): TravelResearch {
        require(json.optString("destination", json.optString("city")) == destination && (!json.has("city") || json.optString("city") == destination)) { "研究结果与当前城市不一致，请重新检索。" }
        val sources = sources(json.optJSONArray("sources"))
        val knownSources = sources.map { it.id }.toSet()
        val candidateArray = json.getJSONArray("candidates"); require(candidateArray.length() <= 12)
        val candidates = candidateArray.objects().map { item ->
            val category = item.getString("category")
            require(category in setOf("sights", "food", "experiences"))
            val duration = item.integer("duration"); require(duration in 15..720)
            val sourceIds = item.strings("sourceIds"); require(sourceIds.isNotEmpty() && knownSources.containsAll(sourceIds))
            TravelCandidate(item.getString("id"), item.getString("title").take(120), category, item.optString("description").take(1000), duration,
                item.optString("region").take(200), item.optString("locationStatus", "pending"), imagePath(item.optJSONObject("image")?.optString("url")), sourceIds)
        }
        require(candidates.size <= 12 && candidates.map { it.id }.distinct().size == candidates.size)
        return TravelResearch(json.getString("id"), destination, candidates, sources, json.strings("warnings"), json.optString("fetchedAt"))
    }
    fun unplaced(json: JSONObject, selected: Set<String>): List<TravelUnplaced> {
        val array = json.optJSONArray("unplaced"); require(array == null || array.length() <= selected.size)
        val result = array?.objects().orEmpty().map {
            require(it.getString("id") in selected && it.getString("reason").isNotBlank())
            TravelUnplaced(it.getString("id"), it.getString("title").take(120), it.getString("reason").take(300))
        }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }
    fun trip(result: JSONObject, research: TravelResearch, selected: Map<String, String>, form: TravelLiveForm): TravelLiveTrip {
        require(result.getString("status") == "ready")
        val json = result.getJSONObject("trip")
        require(json.optString("researchId") == research.id)
        require(json.optBoolean("undated") == form.undated)
        require(if (form.undated) json.isNull("date") else json.optString("date") == form.date)
        val sources = sources(json.optJSONArray("sources"))
        val selectedIds = selected.keys
        val known = research.candidates.associateBy { it.id }
        val allIds = mutableListOf<String>()
        val fixedIds = mutableSetOf<String>()
        val dayArray = json.getJSONArray("days"); require(dayArray.length() == form.days)
        val days = dayArray.objects().mapIndexed { index, day ->
            require(day.integer("day") == index + 1)
            val expectedDate = if (form.undated) null else LocalDate.parse(form.date).plusDays(index.toLong()).toString()
            require(day.nullableString("date") == expectedDate)
            var end = -1
            val stopArray = day.getJSONArray("stops"); require(stopArray.length() <= 15)
            val stops = stopArray.objects().map { stop ->
                val id = stop.getString("id")
                val selectionId = stop.nullableString("selectionId")
                if (selectionId != null) { require(selectionId in selectedIds && id == selectionId); allIds += selectionId }
                else {
                    require(fixedIds.add(id))
                    require(when (id) {
                        "__arrival" -> form.arrival.isNotEmpty() && index == 0 && stop.optString("time") == form.arrival
                        "__hotel" -> form.hotel.isNotEmpty() && index == 0 && stop.optString("title") == form.hotel
                        "__return" -> form.returnTime.isNotEmpty() && index == form.days - 1 && stop.optString("time") == form.returnTime
                        else -> false
                    })
                }
                val title = stop.getString("title"); require(title.length in 1..120)
                if (selectionId != null) require(title == known.getValue(selectionId).title)
                val time = stop.getString("time"); require(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(time))
                val start = time.take(2).toInt() * 60 + time.takeLast(2).toInt()
                val duration = stop.integer("duration"); require(duration in 1..720 && start >= end && start + duration <= 1440); end = start + duration
                TravelLiveStop(id, selectionId, title, time, duration, stop.optString("notes").take(1000), stop.optString("locationStatus", "pending"),
                    selected[selectionId] == "must" || stop.optBoolean("mustKeep"), stop.strings("sourceIds"),
                    if (stop.optString("travelStatus") == "estimated" && !stop.isNull("travelMinutes")) stop.integer("travelMinutes").also { require(it in 1..1440) } else null,
                    stop.optString("travelCheckedAt"), stop.optString("region").take(200), stop.optJSONArray("sourceRefs")?.toString().orEmpty(), stop.optJSONObject("verification")?.toString().orEmpty())
            }
            require(stops.size <= 15)
            TravelLiveDay(index + 1, expectedDate, stops)
        }
        val expectedFixed = buildSet { if (form.arrival.isNotEmpty()) add("__arrival"); if (form.hotel.isNotEmpty()) add("__hotel"); if (form.returnTime.isNotEmpty()) add("__return") }
        require(fixedIds == expectedFixed && days.size == form.days && allIds.size == selectedIds.size && allIds.toSet() == selectedIds && allIds.distinct().size == allIds.size) { "编排结果遗漏、重复或增加了所选内容，请重试。" }
        return TravelLiveTrip(json.getString("id"), json.getString("title").take(120), days, sources, result.strings("warnings"), json.optString("planningNote").take(1000), form, result.toString())
    }

    fun roomDraft(trip: TravelLiveTrip): Pair<TripEntity, List<TripStopDraft>> {
        require(!trip.form.undated && validDate(trip.form.date)) { "请先确定出发日期并重新编排，再保存到我的行程。" }
        require(trip.evidenceJson.isNotBlank()) { "完整行程资料缺失，请重新编排。" }
        // Full public source/verification/traffic data survives independently of bounded editor notes.
        fun summary(parts: List<String>): String = (parts.filter { it.isNotBlank() }.joinToString("\n").take(950) + "\n完整来源与交通记录已保存在行程资料中。")
        val entity = TripEntity(title = trip.title, destination = trip.form.destination, startDate = trip.form.date, dayCount = trip.days.size,
            timeZoneId = "Asia/Shanghai", transport = trip.form.mode, pace = trip.form.pace,
            notes = summary(listOf(trip.planningNote, "真实攻略 AI 编排；保存不会自动创建提醒。") + trip.warnings), liveEvidenceJson = trip.evidenceJson)
        val sources = trip.sources.associateBy { it.id }
        val stops = trip.days.flatMap { day -> day.stops.mapIndexed { index, stop ->
            val refs = stop.sourceIds.mapNotNull { sources[it] }.joinToString("\n") { "来源：${it.title}\n${it.url}" }
            val traffic = stop.travelMinutes?.let { "生成时交通记录：估计 $it 分钟；查询于 ${stop.travelCheckedAt}，出行前仍需核对。" } ?: "生成时交通记录：相邻交通尚未核对；预留间隔不代表实际耗时。"
            val location = when {
                stop.selectionId != null -> stop.title
                stop.id == "__hotel" -> trip.form.hotel
                else -> ""
            }
            TripStopDraft(dayIndex = day.day - 1, position = index, title = stop.title, location = location, startMinute = stop.time.take(2).toInt()*60 + stop.time.takeLast(2).toInt(),
                durationMinutes = stop.duration, notes = summary(listOf("以下为生成时记录；手动修改后需重新核对地点与交通。", if (stop.mustKeep) "用户必选" else "", "生成时位置状态：${stop.locationStatus}", traffic, refs, stop.notes)))
        } }
        return entity to stops
    }
}
