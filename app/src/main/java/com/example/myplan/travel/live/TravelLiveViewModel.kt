package com.example.myplan.travel.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.myplan.travel.data.TravelDatabase
import com.example.myplan.travel.data.TravelRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One immutable input snapshot belongs to each task; merely leaving a screen never cancels server execution. */
class TravelLiveViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val gatewayFactory: (TravelLiveConfig) -> TravelLiveGateway = { TravelLiveApi(it) },
    private val saveOverride: (suspend (TravelLiveTrip) -> Long)? = null,
    private val pollInterval: Long = 1200,
) : AndroidViewModel(application) {
    private val configuration = TravelLiveConfiguration(application)
    private var gateway: TravelLiveGateway? = null
    private var task: Job? = null
    private var revision = 0L
    private data class Pending(val kind: String, val id: String, val destination: String, val research: TravelResearch?,
        val selected: Map<String, String>, val form: TravelLiveForm, val revision: Long)
    private var pending: Pending? = null
    private val imageCache = object : LinkedHashMap<String, ByteArray>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6 || values.sumOf { it.size.toLong() } > 12L * 1024 * 1024
    }
    private val imageRequests = mutableMapOf<String, Deferred<ByteArray?>>()
    private val _state = MutableStateFlow(restore().copy(config = configuration.load()))
    val state = _state.asStateFlow()

    private fun api(): TravelLiveGateway = gateway ?: gatewayFactory(_state.value.config).also { gateway = it }
    private fun update(block: (TravelLiveState) -> TravelLiveState) {
        _state.update(block)
        val state = _state.value
        savedState["live_destination"] = state.destination
        savedState["live_form"] = state.form.json().toString()
        savedState["live_selected"] = JSONObject(state.selected).toString()
        savedState["live_saved_id"] = state.savedTripId
        savedState["live_has_job"] = state.activeJobId != null
    }
    private fun restore(): TravelLiveState {
        val destination = savedState.get<String>("live_destination").orEmpty()
        val form = runCatching { val j = JSONObject(savedState.get<String>("live_form") ?: "{}"); TravelLiveForm(destination,
            j.optInt("days", 2), j.optString("date"), j.optBoolean("undated", true), j.optString("arrival"), j.optString("returnTime"), j.optString("hotel"), j.optString("pace", "舒缓"), j.optString("mode", "公共交通")) }.getOrDefault(TravelLiveForm(destination))
        val research = runCatching { TravelLiveJson.research(JSONObject(savedState.get<String>("live_research") ?: ""), destination) }.getOrNull()
        val selected = runCatching { val j = JSONObject(savedState.get<String>("live_selected") ?: "{}"); j.keys().asSequence().associateWith { j.getString(it) }.filter { (id, p) -> research?.candidates?.any { it.id == id } == true && p in setOf("want", "must") } }.getOrDefault(emptyMap())
        val error = if (savedState.get<Boolean>("live_has_job") == true || research != null) "已恢复本机清单和条件。进程重启后需重新连接并检索，原服务任务不会自动取消。" else null
        return TravelLiveState(destination = destination, form = form, research = research, selected = selected,
            phase = if (research != null) TravelLivePhase.CANDIDATES else TravelLivePhase.INPUT, error = error, savedTripId = savedState["live_saved_id"])
    }
    fun setConfig(config: TravelLiveConfig) {
        if (_state.value.busy) return
        try {
            val clean = config.copy(baseUrl = config.baseUrl.trim().trimEnd('/'), token = config.token.trim())
            configuration.save(clean); gateway = null; pending = null; revision++
            val oldImages = synchronized(imageRequests) { imageRequests.values.toList().also { imageRequests.clear() } }
            oldImages.forEach { it.cancel() }
            synchronized(imageCache) { imageCache.clear() }
            update { it.copy(config = clean, connection = "配置已保存，请检测连接", activeJobId = null, error = null) }
        } catch (e: Exception) { update { it.copy(error = message(e)) } }
    }
    fun testConnection() {
        if (_state.value.busy) return
        task = viewModelScope.launch {
            update { it.copy(busy = true, error = null, progress = "正在连接电脑旅行服务…") }
            try {
                val result = api().session()
                update { it.copy(connection = if (result.loggedIn && result.research && result.ai) "攻略可检索 · AI 可规划" + if (result.map) " · 地图可核对" else " · 地图待配置" else result.message,
                    error = if (!result.loggedIn) result.message else if (!result.ai) "电脑端 AI 尚未配置，请完成配置后重启服务。" else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = message(e), connection = "尚未连接") } }
            finally { update { it.copy(busy = false, progress = "") } }
        }
    }
    fun search(destination: String) {
        if (_state.value.busy) return
        val city = destination.trim()
        if (city.length !in 1..40) { update { it.copy(error = "请填写 1 至 40 字的旅行城市。") }; return }
        if (pending != null) { update { it.copy(error = "仍有上次任务可继续查看，请先继续原任务，或重新连接后再检索。") }; return }
        val token = ++revision
        task = viewModelScope.launch {
            update { it.copy(busy = true, progress = "正在检索${city}的攻略…", error = null) }
            try {
                val id = api().research(city)
                pending = Pending("research", id, city, null, emptyMap(), _state.value.form.copy(destination = city), token)
                update { it.copy(activeJobId = id) }; poll()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleTaskFailure(e) }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun toggleCandidate(id: String) {
        if (_state.value.busy || pending != null || _state.value.research?.candidates?.none { it.id == id } != false) return
        revision++
        update { it.copy(selected = it.selected.toMutableMap().apply { if (containsKey(id)) remove(id) else put(id, "want") }, trip = null, savedTripId = null, unplaced = emptyList(), error = null) }
    }
    fun markMust(id: String) {
        if (_state.value.busy || pending != null || id !in _state.value.selected) return
        revision++; update { it.copy(selected = it.selected + (id to if (it.selected[id] == "must") "want" else "must"), trip = null, savedTripId = null, unplaced = emptyList()) }
    }
    fun showWishlist() { if (!_state.value.busy) update { it.copy(phase = TravelLivePhase.WISHLIST) } }
    fun showConditions() {
        if (_state.value.busy) return
        if (_state.value.selected.isEmpty()) update { it.copy(error = "先加入至少一项想去、想吃或想体验的内容。") }
        else update { it.copy(phase = TravelLivePhase.CONDITIONS, error = null) }
    }
    fun back() {
        if (_state.value.busy) { stopViewing(); return }
        update { it.copy(phase = when (it.phase) {
            TravelLivePhase.RESULT -> TravelLivePhase.CONDITIONS
            TravelLivePhase.CONDITIONS -> TravelLivePhase.WISHLIST
            TravelLivePhase.WISHLIST -> TravelLivePhase.CANDIDATES
            else -> TravelLivePhase.INPUT
        }, error = null) }
    }
    fun setForm(form: TravelLiveForm) {
        if (_state.value.busy || pending != null) return
        revision++; update { it.copy(form = form.copy(destination = it.research?.destination ?: it.destination), trip = null, savedTripId = null, unplaced = emptyList(), error = null) }
    }
    fun generate() {
        val state = _state.value
        if (state.busy) return
        if (pending != null) { update { it.copy(error = "请先继续查看上一次任务，或重新连接后再编排。") }; return }
        val research = state.research ?: return
        try { state.form.validate(); require(state.selected.isNotEmpty()) { "请先选择旅行内容。" } }
        catch (e: Exception) { update { it.copy(error = message(e)) }; return }
        val snapshot = state.selected.toMap(); val form = state.form.copy(destination = research.destination); val token = revision
        task = viewModelScope.launch {
            update { it.copy(busy = true, progress = "正在提交已选清单…", error = null) }
            try {
                val id = api().plan(research.id, snapshot, form)
                pending = Pending("plan", id, research.destination, research, snapshot, form, token)
                update { it.copy(activeJobId = id) }; poll()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleTaskFailure(e) }
            finally { update { it.copy(busy = false) } }
        }
    }
    private suspend fun poll() {
        val snapshot = pending ?: return
        val start = System.nanoTime()
        while ((System.nanoTime() - start) / 1_000_000 < 8 * 60 * 1000) {
            val job = api().job(snapshot.id)
            if (snapshot.revision != revision || pending !== snapshot) return
            require(job.kind == snapshot.kind)
            update { it.copy(progress = job.progress) }
            when (job.status) {
                "failed" -> { pending = null; update { it.copy(activeJobId = null) }; throw TravelLiveApi.error(job.errorCode ?: "JOB_FAILED") }
                "done" -> {
                    pending = null; update { it.copy(activeJobId = null) }
                    val result = job.result ?: throw TravelLiveApi.error("INVALID_RESPONSE")
                    if (snapshot.kind == "research") {
                        val research = TravelLiveJson.research(result, snapshot.destination)
                        // Keep an explicitly bounded, public-only snapshot for process recreation.
                        val safeSnapshot = result.toString()
                        if (safeSnapshot.toByteArray().size <= 100 * 1024) savedState["live_research"] = safeSnapshot else savedState.remove<String>("live_research")
                        synchronized(imageCache) { imageCache.clear() }
                        update { it.copy(destination = research.destination, research = research, selected = emptyMap(), form = snapshot.form, phase = TravelLivePhase.CANDIDATES, trip = null, savedTripId = null, unplaced = emptyList()) }
                    } else if (result.optString("status") == "needs_changes") {
                        val unplaced = TravelLiveJson.unplaced(result, snapshot.selected.keys)
                        require(unplaced.isNotEmpty())
                        update { it.copy(unplaced = unplaced, trip = null, phase = TravelLivePhase.CONDITIONS, error = "部分所选内容未排入，请调整天数、时间或清单后重试。所有选择均已保留。") }
                    } else {
                        val trip = TravelLiveJson.trip(result, snapshot.research!!, snapshot.selected, snapshot.form)
                        update { it.copy(trip = trip, phase = TravelLivePhase.RESULT, unplaced = emptyList(), savedTripId = null) }
                    }
                    pending = null; update { it.copy(activeJobId = null, progress = "") }; return
                }
            }
            delay(pollInterval)
        }
        update { it.copy(error = "任务暂未完成，可以稍后继续查看。") }
    }
    fun stopViewing() {
        if (_state.value.saving) return
        task?.cancel(); update { it.copy(busy = false, progress = "已停止查看，电脑端任务可能仍在进行。") }
    }
    fun resumeJob() {
        if (_state.value.busy || pending == null) return
        task = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try { poll() } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is TravelLiveException && e.code in setOf("JOB_NOT_FOUND", "SESSION_REQUIRED")) { pending = null; update { it.copy(activeJobId = null) } }
                update { it.copy(error = message(e)) }
            } finally { update { it.copy(busy = false) } }
        }
    }
    suspend fun loadImage(path: String): ByteArray? {
        if (TravelLiveJson.imagePath(path) == null) return null
        synchronized(imageCache) { imageCache[path]?.let { return it } }
        val request = synchronized(imageRequests) {
            imageRequests[path] ?: viewModelScope.async(start = CoroutineStart.LAZY) {
                val owner = api()
                val image = owner.image(path)
                if (image != null && gateway === owner) synchronized(imageCache) { imageCache[path] = image }
                image
            }.also { deferred ->
                imageRequests[path] = deferred
                deferred.invokeOnCompletion { synchronized(imageRequests) { if (imageRequests[path] === deferred) imageRequests.remove(path) } }
            }
        }
        request.start()
        return request.await()
    }
    fun dismissError() { update { it.copy(error = null) } }
    fun saveTrip(onSaved: (Long) -> Unit) {
        if (_state.value.busy) return
        _state.value.savedTripId?.let { onSaved(it); return }
        val trip = _state.value.trip ?: return
        try { TravelLiveJson.roomDraft(trip) } catch (e: Exception) { update { it.copy(error = message(e)) }; return }
        // Claim the save synchronously, before a second tap or a back action can interleave.
        update { it.copy(busy = true, saving = true, error = null) }
        task = viewModelScope.launch {
            try {
                val id = withContext(NonCancellable) {
                    val savedId = saveOverride?.invoke(trip) ?: withContext(Dispatchers.IO) {
                        val draft = TravelLiveJson.roomDraft(trip)
                        TravelRepository(TravelDatabase.getInstance(getApplication())).saveTrip(draft.first, draft.second)
                    }
                    // The committed identity is recorded in the same non-cancellable section.
                    update { it.copy(savedTripId = savedId) }
                    savedId
                }
                onSaved(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = message(e)) } }
            finally { update { it.copy(busy = false, saving = false) } }
        }
    }
    private fun message(error: Exception) = when (error) {
        is TravelLiveException -> error.message.orEmpty()
        is IllegalArgumentException -> error.message?.takeIf { it.any { char -> char.code > 127 } } ?: "返回内容未通过校验，请重新检索或编排。"
        else -> "操作未完成，请检查电脑连接后重试。"
    }
    private fun handleTaskFailure(error: Exception) {
        if (error is TravelLiveException && error.code in setOf("JOB_NOT_FOUND", "SESSION_REQUIRED", "CSRF_REJECTED", "RESEARCH_EXPIRED")) {
            pending = null; update { it.copy(activeJobId = null) }
        }
        update { it.copy(error = message(error)) }
    }
}
