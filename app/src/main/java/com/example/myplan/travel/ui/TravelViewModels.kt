package com.example.myplan.travel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.myplan.assistant.api.*
import com.example.myplan.travel.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

data class TripEditorDraft(
    val id: Long = 0, val title: String = "", val destination: String = "",
    val startDate: String = LocalDate.now().toString(), val dayCount: String = "2",
    val timeZoneId: String = ZoneId.systemDefault().id, val transport: String = "公共交通",
    val pace: String = "适中", val notes: String = "",
) {
    fun entity(): TripEntity {
        val days = dayCount.toIntOrNull()
        require(days != null && days in 1..14) { "行程天数需要在 1 至 14 天之间" }
        require(title.trim().length in 1..120) { "请填写 1 至 120 字的行程名称" }
        require(destination.trim().length in 1..200) { "请填写 1 至 200 字的目的地" }
        require(notes.length <= 1000) { "行程备注最多 1000 字" }
        try { LocalDate.parse(startDate); ZoneId.of(timeZoneId.trim()) }
        catch (_: DateTimeException) { throw IllegalArgumentException("请检查出发日期和时区，例如 Asia/Shanghai") }
        require(timeZoneId.trim() in ZoneId.getAvailableZoneIds()) { "请选择 IANA 时区，例如 Asia/Shanghai；可使用下方常用时区。" }
        return TripEntity(id, title.trim(), destination.trim(), startDate, days, timeZoneId.trim(), transport, pace, notes)
    }
}

data class TripStopEditorDraft(
    val id: Long = 0, val dayIndex: Int = 0, val position: Int = 0, val title: String = "",
    val location: String = "", val startTime: String = "09:00", val duration: String = "60", val notes: String = "",
    val isCompleted: Boolean = false, val key: String = UUID.randomUUID().toString(), val selected: Boolean = true,
) {
    fun draft(): TripStopDraft {
        require(title.trim().length in 1..120) { "请填写 1 至 120 字的节点名称" }
        require(location.length <= 200 && notes.length <= 1000) { "地点最多 200 字，备注最多 1000 字" }
        require(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(startTime)) { "请使用 24 小时制时间，例如 09:30" }
        val time = LocalTime.parse(startTime)
        val minutes = duration.toIntOrNull()
        require(minutes != null && minutes in 1..720) { "节点时长需要在 1 至 720 分钟之间" }
        return TripStopDraft(id, dayIndex, position, title.trim(), location.trim(), time.hour * 60 + time.minute, minutes, notes, isCompleted)
    }
}

internal fun TripEntity.editor() = TripEditorDraft(id, title, destination, startDate, dayCount.toString(), timeZoneId, transport, pace, notes)
internal fun TripStopEntity.editor() = TripStopEditorDraft(id, dayIndex, position, title, location,
    "%02d:%02d".format(startMinute / 60, startMinute % 60), durationMinutes.toString(), notes, isCompleted, "saved:$id")

data class TravelHomeState(val trips: List<TripWithStops> = emptyList(), val loading: Boolean = true,
    val draft: TripEditorDraft? = null, val busy: Boolean = false, val error: String? = null)

class TravelHomeViewModel(application: Application, private val savedState: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val repo = TravelRepository(TravelDatabase.getInstance(application))
    private val _state = MutableStateFlow(TravelHomeState(draft = savedState.get<String>("trip_draft")?.let(TravelEditorCodec::readTrip)))
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            repo.observeTrips().catch { update { it.copy(loading = false, error = "行程读取失败，请稍后重新打开。") } }
                .collect { trips -> update { it.copy(trips = trips, loading = false) } }
        }
    }
    private fun update(block: (TravelHomeState) -> TravelHomeState) {
        _state.update(block); savedState["trip_draft"] = _state.value.draft?.let(TravelEditorCodec::trip)
    }
    fun create() { if (!_state.value.busy) update { it.copy(draft = TripEditorDraft(), error = null) } }
    fun edit(trip: TripEntity) { if (!_state.value.busy) update { it.copy(draft = trip.editor(), error = null) } }
    fun change(draft: TripEditorDraft) { if (!_state.value.busy) update { it.copy(draft = draft, error = null) } }
    fun dismiss() { if (!_state.value.busy) update { it.copy(draft = null, error = null) } }
    fun save(onSaved: (Long) -> Unit) {
        val draft = _state.value.draft ?: return
        if (_state.value.busy) return
        val trip = try { draft.entity() } catch (e: IllegalArgumentException) { update { it.copy(error = e.message) }; return }
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { repo.saveTrip(trip) }
                update { it.copy(draft = null) }
                onSaved(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = safeTravelMessage(e, "保存行程失败，请重试。")) } }
            finally { update { it.copy(busy = false) } }
        }
    }
}

data class TravelDetailState(
    val data: TripWithStops? = null, val loading: Boolean = true, val selectedDay: Int = 0,
    val tripDraft: TripEditorDraft? = null, val stopDraft: TripStopEditorDraft? = null,
    val suggestions: List<TripStopEditorDraft> = emptyList(), val generating: Boolean = false,
    val busy: Boolean = false, val aiError: String? = null, val error: String? = null,
)

class TravelDetailViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val generateTrip: (suspend (TravelAssistRequest) -> TravelAssistResult)? = null,
) : AndroidViewModel(application) {
    private val repo = TravelRepository(TravelDatabase.getInstance(application))
    private val assistant by lazy { TravelAssistService({ AssistConfiguration(application).load() }) }
    private val _state = MutableStateFlow(TravelDetailState(selectedDay = savedState["day"] ?: 0,
        tripDraft = savedState.get<String>("trip_draft")?.let(TravelEditorCodec::readTrip),
        stopDraft = savedState.get<String>("stop_draft")?.let(TravelEditorCodec::readStop),
        suggestions = savedState.get<String>("suggestions")?.let(TravelEditorCodec::readStops).orEmpty()))
    val state = _state.asStateFlow()
    private var tripId: Long = savedState["trip_id"] ?: 0L
    private var sourceVersion: Long? = savedState["source_version"]
    private var observeJob: Job? = null
    private var aiJob: Job? = null
    private var generation = 0L

    private fun update(block: (TravelDetailState) -> TravelDetailState) {
        _state.update(block)
        val state = _state.value
        savedState["day"] = state.selectedDay
        savedState["trip_draft"] = state.tripDraft?.let(TravelEditorCodec::trip)
        savedState["stop_draft"] = state.stopDraft?.let(TravelEditorCodec::stop)
        savedState["suggestions"] = state.suggestions.takeIf { it.isNotEmpty() }?.let(TravelEditorCodec::stops)
        savedState["source_version"] = sourceVersion
    }
    fun load(id: Long) {
        if (id == tripId && observeJob != null) return
        if (id != tripId) {
            cancelGeneration(); sourceVersion = null
            update { TravelDetailState() }
        }
        tripId = id; savedState["trip_id"] = id
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeTrip(id).catch { update { it.copy(loading = false, error = "读取行程失败，请返回后重试。") } }
                .collect { data ->
                    if (tripId != id) return@collect
                    if (sourceVersion != null && sourceVersion != data?.trip?.updatedAt) {
                        cancelGeneration(); sourceVersion = null
                        update { it.copy(suggestions = emptyList(), aiError = "行程已经变化，请重新生成建议。") }
                    }
                    update { it.copy(data = data, loading = false,
                        selectedDay = it.selectedDay.coerceIn(0, ((data?.trip?.dayCount ?: 1) - 1).coerceAtLeast(0))) }
                }
        }
    }
    fun selectDay(day: Int) { val trip = _state.value.data?.trip ?: return; if (day in 0 until trip.dayCount) update { it.copy(selectedDay = day) } }
    fun editTrip() { val trip = _state.value.data?.trip ?: return; if (!_state.value.busy) { cancelGeneration(); update { it.copy(tripDraft = trip.editor(), error = null) } } }
    fun changeTrip(draft: TripEditorDraft) { if (!_state.value.busy) update { it.copy(tripDraft = draft, error = null) } }
    fun dismissTrip() { if (!_state.value.busy) update { it.copy(tripDraft = null, error = null) } }
    fun saveTrip() {
        val draft = _state.value.tripDraft ?: return
        val trip = try { draft.entity() } catch (e: IllegalArgumentException) { update { it.copy(error = e.message) }; return }
        mutate("保存行程失败，请重试。") { repo.saveTrip(trip); update { it.copy(tripDraft = null) } }
    }
    fun addStop() {
        val state = _state.value; val trip = state.data ?: return
        if (state.busy) return
        cancelGeneration()
        val day = trip.stops.filter { it.dayIndex == state.selectedDay }
        val nextMinute = day.lastOrNull()?.let { (it.startMinute + it.durationMinutes + 30).coerceAtMost(1439) } ?: 540
        update { it.copy(stopDraft = TripStopEditorDraft(dayIndex = it.selectedDay, position = day.size,
            startTime = "%02d:%02d".format(nextMinute / 60, nextMinute % 60)), error = null) }
    }
    fun editStop(stop: TripStopEntity) { if (!_state.value.busy) { cancelGeneration(); update { it.copy(stopDraft = stop.editor(), error = null) } } }
    fun changeStop(draft: TripStopEditorDraft) { if (!_state.value.busy) update { it.copy(stopDraft = draft, error = null) } }
    fun dismissStop() { if (!_state.value.busy) update { it.copy(stopDraft = null, error = null) } }
    fun saveStop() {
        val edit = _state.value.stopDraft ?: return
        val draft = try { edit.draft() } catch (e: IllegalArgumentException) { update { it.copy(error = e.message) }; return }
        mutate("保存节点失败，请重试。") { repo.saveStop(tripId, draft); update { it.copy(stopDraft = null, selectedDay = draft.dayIndex) } }
    }
    fun deleteStop(stop: TripStopEntity) = mutate("删除节点失败，请重试。") { repo.deleteStop(tripId, stop.id) }
    fun moveStop(stop: TripStopEntity, delta: Int) = mutate("调整顺序失败，请重试。") { repo.moveStop(tripId, stop.id, delta) }
    fun complete(stop: TripStopEntity) = mutate("节点状态未能保存，请重试。") { repo.setCompleted(tripId, stop.id, !stop.isCompleted) }
    fun deleteTrip(onDeleted: () -> Unit) = mutate("删除行程失败，请重试。") { repo.deleteTrip(tripId); onDeleted() }

    private fun mutate(fallback: String, action: suspend () -> Unit) {
        if (_state.value.busy) return
        cancelGeneration(); sourceVersion = null
        update { it.copy(busy = true, error = null, suggestions = emptyList()) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = safeTravelMessage(e, fallback)) } }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun withReminder(stop: TripStopEntity, onReady: (TripEntity, TripStopEntity) -> Unit) {
        val trip = _state.value.data?.trip ?: return
        if (_state.value.busy) return
        try {
            require(TripSchedule.resolveInstant(trip, stop).isAfter(Instant.now())) { "这个节点时间已经过去，请先调整到未来再另存为提醒。" }
            onReady(trip, stop)
        } catch (e: IllegalArgumentException) { update { it.copy(error = e.message) } }
    }
    fun requestSuggestions() {
        val trip = _state.value.data?.trip ?: return
        if (_state.value.busy || _state.value.generating) return
        val config = AssistConfiguration(getApplication()).load()
        if (generateTrip == null && !config.isConfigured) { update { it.copy(aiError = "请先配置 AI 服务。也可以手动添加节点，离线完成行程安排。") }; return }
        val token = ++generation
        sourceVersion = trip.updatedAt
        update { it.copy(generating = true, suggestions = emptyList(), aiError = null, error = null) }
        aiJob = viewModelScope.launch {
            try {
                val request = TravelAssistRequest(trip.destination, trip.title, trip.startDate, trip.dayCount, trip.timeZoneId, trip.transport, trip.pace, trip.notes)
                val result = generateTrip?.invoke(request) ?: assistant.generate(request)
                ensureActive()
                if (token != generation || sourceVersion != _state.value.data?.trip?.updatedAt) return@launch
                val suggestions = result.days.sortedBy { it.day }.flatMap { day ->
                    day.stops.sortedBy { it.startTime }.mapIndexed { index, stop -> TripStopEditorDraft(dayIndex = day.day - 1,
                        position = index, title = stop.title, location = stop.location, startTime = stop.startTime,
                        duration = stop.durationMinutes.toString(), notes = stop.notes) }
                }
                update { it.copy(suggestions = suggestions, generating = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) update { it.copy(generating = false, aiError = safeTravelMessage(e, "这次未能生成建议，可以重试或手动添加。")) } }
        }
    }
    fun cancelGeneration() {
        aiJob?.cancel(); aiJob = null; generation++
        if (_state.value.generating) {
            sourceVersion = null
            update { it.copy(generating = false) }
        }
    }
    fun dismissSuggestions() { sourceVersion = null; update { it.copy(suggestions = emptyList(), error = null) } }
    fun changeSuggestion(key: String, draft: TripStopEditorDraft) { if (!_state.value.busy) update { it.copy(suggestions = it.suggestions.map { s -> if (s.key == key) draft.copy(id = 0, key = key) else s }, error = null) } }
    fun adoptSuggestions(append: Boolean) {
        if (_state.value.busy) return
        val state = _state.value
        if (state.suggestions.none { it.selected }) { update { it.copy(error = "请至少选择一个节点。") }; return }
        if (sourceVersion != state.data?.trip?.updatedAt) { update { it.copy(error = "行程已变化，请重新生成后再采纳。") }; return }
        val selected = try { state.suggestions.filter { it.selected }.map { it.draft() } }
            catch (e: IllegalArgumentException) { update { it.copy(error = e.message) }; return }
        cancelGeneration()
        val originalVersion = sourceVersion
        sourceVersion = null
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.replaceStops(tripId, selected, append)
                sourceVersion = null
                update { it.copy(suggestions = emptyList()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                sourceVersion = originalVersion
                update { it.copy(error = safeTravelMessage(e, "采纳建议失败，原行程仍然保留。")) }
            }
            finally { update { it.copy(busy = false) } }
        }
    }
}

private fun safeTravelMessage(e: Exception, fallback: String): String = when (e) {
    is AssistException -> e.userMessage
    is IllegalArgumentException -> e.message ?: fallback
    else -> fallback
}

internal object TravelEditorCodec {
    fun trip(d: TripEditorDraft): String = JSONObject().apply {
        put("id", d.id); put("title", d.title); put("destination", d.destination); put("date", d.startDate); put("days", d.dayCount)
        put("zone", d.timeZoneId); put("transport", d.transport); put("pace", d.pace); put("notes", d.notes)
    }.toString()
    fun readTrip(s: String): TripEditorDraft? = runCatching { JSONObject(s).let { j -> TripEditorDraft(j.getLong("id"), j.getString("title"), j.getString("destination"),
        j.getString("date"), j.getString("days"), j.getString("zone"), j.getString("transport"), j.getString("pace"), j.getString("notes")) } }.getOrNull()
    private fun json(d: TripStopEditorDraft) = JSONObject().apply {
        put("id", d.id); put("day", d.dayIndex); put("position", d.position); put("title", d.title); put("location", d.location)
        put("time", d.startTime); put("duration", d.duration); put("notes", d.notes); put("done", d.isCompleted); put("key", d.key); put("selected", d.selected)
    }
    fun stop(d: TripStopEditorDraft): String = json(d).toString()
    private fun read(j: JSONObject) = TripStopEditorDraft(j.getLong("id"), j.getInt("day"), j.getInt("position"), j.getString("title"), j.getString("location"),
        j.getString("time"), j.getString("duration"), j.getString("notes"), j.getBoolean("done"), j.getString("key"), j.getBoolean("selected"))
    fun readStop(s: String): TripStopEditorDraft? = runCatching { read(JSONObject(s)) }.getOrNull()
    fun stops(d: List<TripStopEditorDraft>): String = JSONArray(d.map(::json)).toString()
    fun readStops(s: String): List<TripStopEditorDraft> = runCatching { JSONArray(s).let { j -> (0 until j.length()).map { read(j.getJSONObject(it)) } } }.getOrDefault(emptyList())
}
