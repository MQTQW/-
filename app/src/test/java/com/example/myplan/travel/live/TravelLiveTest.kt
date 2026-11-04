package com.example.myplan.travel.live

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.travel.TravelActions
import com.example.myplan.travel.data.TripStopEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TravelLiveTest {
    private val code = "a".repeat(64)
    private val config = TravelLiveConfig(token = code)
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var application: Application
    private val form = TravelLiveForm("测试城市", 1, "2030-11-04", false)
    private val source = JSONObject().put("id", "s1").put("title", "测试公开攻略").put("author", "公开作者").put("url", "https://www.xiaohongshu.com/explore/test")
    private fun research() = JSONObject().put("id", "research-test").put("destination", "测试城市").put("sources", JSONArray().put(source))
        .put("candidates", JSONArray().put(JSONObject().put("id", "a").put("title", "测试景点").put("category", "sights").put("description", "来源文字")
            .put("duration", 60).put("sourceIds", JSONArray().put("s1")).put("locationStatus", "pending")))
    private fun stop(id: String = "a", time: String = "09:00") = JSONObject().put("id", id).put("selectionId", id).put("title", "测试景点")
        .put("time", time).put("duration", 60).put("notes", "地点待核对").put("locationStatus", "pending").put("sourceIds", JSONArray().put("s1"))
    private fun result(stops: JSONArray = JSONArray().put(stop())) = JSONObject().put("status", "ready").put("warnings", JSONArray().put("交通仍需确认"))
        .put("trip", JSONObject().put("id", "trip-test").put("researchId", "research-test").put("title", "测试城市1日行程").put("date", form.date).put("undated", false)
            .put("sources", JSONArray().put(source)).put("planningNote", "真实 AI 编排")
            .put("days", JSONArray().put(JSONObject().put("day", 1).put("date", form.date).put("stops", stops))))
    private fun invalid(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Before fun prepare() { Dispatchers.setMain(dispatcher); application = ApplicationProvider.getApplicationContext(); TravelLiveConfiguration(application).clear() }
    @After fun finish() { store.clear(); Dispatchers.resetMain() }

    @Test fun configurationRejectsRemoteHttpEmbeddedCredentialsAndQueryTokens() {
        assertEquals("127.0.0.1", config.validatedUrl(debug = true).host)
        for (url in listOf("http://10.0.2.2:8794", "http://192.168.1.2:8794", "https://example.com", "http://a:b@127.0.0.1:8794", "http://127.0.0.1:8794?token=x", "http://127.0.0.1:8794/api")) {
            try { config.copy(baseUrl = url).validatedUrl(true); fail(url) } catch (_: TravelLiveException) {}
        }
        try { config.validatedUrl(debug = false); fail() } catch (_: TravelLiveException) {}
        assertFalse(config.toString().contains(code))
    }
    @Test fun nativeApiUsesBearerCookieCsrfAndImageRewriteWithoutOrigin() = runBlocking {
        val routes = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); routes += request.url.encodedPath
            assertEquals("Bearer $code", request.header("Authorization")); assertNull(request.header("Origin"))
            if (!request.url.encodedPath.endsWith("session")) assertEquals("myplan_native_session=private-session", request.header("Cookie"))
            val body = when {
                request.url.encodedPath.endsWith("session") -> JSONObject().put("csrfToken", "csrf-token-with-enough-characters").put("capabilities", JSONObject().put("ai", true).put("research", true)).put("connection", JSONObject().put("loggedIn", true)).toString()
                request.method == "POST" -> {
                    assertEquals("csrf-token-with-enough-characters", request.header("X-CSRF-Token"))
                    val buffer = Buffer(); request.body!!.writeTo(buffer); val sent = JSONObject(buffer.readUtf8())
                    assertEquals("测试城市", sent.getString("destination")); assertFalse(sent.has("token"))
                    "{\"jobId\":\"11111111-1111-4111-8111-111111111111\"}"
                }
                else -> "test-image-bytes"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", if (request.url.encodedPath.contains("images/")) "image/jpeg" else "application/json")
                .apply { if (request.url.encodedPath.endsWith("session")) header("Set-Cookie", "myplan_native_session=private-session; Path=/api/native; HttpOnly; SameSite=Strict") }
                .body(body.toResponseBody()).build()
        }.build()
        val api = TravelLiveApi(config, client)
        assertEquals("11111111-1111-4111-8111-111111111111", api.research("测试城市"))
        assertNotNull(api.image("/api/images/abcdefghijklmnop"))
        assertEquals(listOf("/api/native/session", "/api/native/research", "/api/native/images/abcdefghijklmnop"), routes)
        assertNull(api.image("https://attacker.example/image"))
    }
    @Test fun providerErrorMessagesAreNotEchoedToUser() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
            .body("{\"error\":{\"code\":\"NATIVE_AUTH_REQUIRED\",\"message\":\"$code\"}}".toResponseBody()).build() }.build()
        try { TravelLiveApi(config, client).session(); fail() } catch (e: TravelLiveException) { assertFalse(e.message!!.contains(code)); assertTrue(e.message!!.contains("连接码")) }
    }
    @Test fun selectedOnlyGuardRejectsDuplicatesUnknownItemsDatesAndUnaskedFixedStops() {
        val data = TravelLiveJson.research(research(), "测试城市")
        val selected = mapOf("a" to "must")
        invalid { TravelLiveJson.trip(result(JSONArray().put(stop()).put(stop(time = "12:00"))), data, selected, form) }
        invalid { TravelLiveJson.trip(result(JSONArray().put(stop("unknown"))), data, selected, form) }
        invalid { TravelLiveJson.trip(result(JSONArray()), data, selected, form) }
        invalid { TravelLiveJson.trip(result().apply { getJSONObject("trip").put("date", "2030-11-05") }, data, selected, form) }
        invalid { TravelLiveJson.trip(result(JSONArray().put(stop()).put(stop("__hotel", "15:00").put("selectionId", JSONObject.NULL))), data, selected, form) }
    }
    @Test fun twelveSelectedItemsAndThreeUserFixedStopsFitOneDay() {
        val candidates = JSONArray().apply { repeat(12) { index ->
            put(JSONObject().put("id", "choice-$index").put("title", "所选地点$index").put("category", "sights").put("duration", 15).put("sourceIds", JSONArray().put("s1")))
        } }
        val data = TravelLiveJson.research(research().put("candidates", candidates), "测试城市")
        val selected = data.candidates.associate { it.id to "want" }
        val fixedForm = form.copy(arrival = "08:00", hotel = "用户填写的酒店", returnTime = "20:00")
        fun fixed(id: String, title: String, time: String, duration: Int) = JSONObject().put("id", id).put("selectionId", JSONObject.NULL)
            .put("title", title).put("time", time).put("duration", duration).put("sourceIds", JSONArray())
        val stops = JSONArray().put(fixed("__arrival", "抵达测试城市", "08:00", 30)).put(fixed("__hotel", fixedForm.hotel, "09:00", 45))
        repeat(12) { index ->
            val minute = 10 * 60 + 15 + index * 45
            stops.put(stop("choice-$index", "%02d:%02d".format(minute / 60, minute % 60)).put("title", "所选地点$index").put("duration", 15))
        }
        stops.put(fixed("__return", "返程", "20:00", 30))
        val trip = TravelLiveJson.trip(result(stops), data, selected, fixedForm)
        assertEquals(15, trip.days.single().stops.size)
        assertEquals(selected.keys, trip.days.single().stops.mapNotNull { it.selectionId }.toSet())
        assertEquals(3, trip.days.single().stops.count { it.selectionId == null })
        assertEquals(15, TravelLiveJson.roomDraft(trip).second.size)
    }
    @Test fun mappingPreservesFullEvidenceAndDoesNotPretendMealIsLocated() {
        val raw = result().apply { getJSONObject("trip").getJSONArray("days").getJSONObject(0).getJSONArray("stops").getJSONObject(0).put("verification", JSONObject().put("status", "pending")).put("sourceRefs", JSONArray().put(JSONObject().put("evidenceText", "x".repeat(1500)))) }
        val trip = TravelLiveJson.trip(raw, TravelLiveJson.research(research(), "测试城市"), mapOf("a" to "must"), form)
        val (entity, stops) = TravelLiveJson.roomDraft(trip)
        assertEquals(raw.toString(), entity.liveEvidenceJson)
        assertTrue(entity.notes.contains("交通仍需确认")); assertTrue(stops.single().notes.contains("位置状态：pending"))
        assertTrue(stops.single().notes.contains("https://www.xiaohongshu.com")); assertTrue(stops.single().notes.length <= 1000)
        assertEquals("Asia/Shanghai", entity.timeZoneId); assertEquals(0L, entity.id)
        invalid { TravelLiveJson.roomDraft(trip.copy(form = form.copy(undated = true))) }
    }
    @Test fun savedNodeSearchUsesSelectedPlaceInsteadOfCityRegion() {
        val rawResearch = research().apply { getJSONArray("candidates").getJSONObject(0).put("title", "雷峰塔").put("region", "杭州") }
        val raw = result().apply { getJSONObject("trip").getJSONArray("days").getJSONObject(0).getJSONArray("stops").getJSONObject(0).put("title", "雷峰塔").put("region", "杭州") }
        val trip = TravelLiveJson.trip(raw, TravelLiveJson.research(rawResearch, "测试城市"), mapOf("a" to "want"), form)
        val (entity, stops) = TravelLiveJson.roomDraft(trip)
        val draft = stops.single()
        assertEquals("雷峰塔", draft.location)
        val stop = TripStopEntity(tripId = 1, dayIndex = draft.dayIndex, position = draft.position, title = draft.title, location = draft.location,
            startMinute = draft.startMinute, durationMinutes = draft.durationMinutes, notes = draft.notes)
        assertEquals("测试城市 雷峰塔", TravelActions.mapQuery(entity, stop))
        assertTrue(draft.notes.contains("生成时位置状态")); assertTrue(draft.notes.contains("生成时交通记录"))
        val fixed = trip.days.single().stops.single().copy(id = "__arrival", selectionId = null, title = "抵达杭州")
        val fixedTrip = trip.copy(days = listOf(TravelLiveDay(1, form.date, listOf(fixed))))
        assertEquals("", TravelLiveJson.roomDraft(fixedTrip).second.single().location)
        val hotelTrip = fixedTrip.copy(form = form.copy(hotel = "西湖某酒店"), days = listOf(TravelLiveDay(1, form.date, listOf(fixed.copy(id = "__hotel")))))
        assertEquals("西湖某酒店", TravelLiveJson.roomDraft(hotelTrip).second.single().location)
    }

    private class Gateway(val research: JSONObject) : TravelLiveGateway {
        var id = 0; var currentKind = "research"; var gate: CompletableDeferred<TravelLiveJob>? = null
        var planResult: JSONObject? = null
        override suspend fun session() = TravelConnection(true, true, false, true, "已连接")
        override suspend fun research(destination: String): String { currentKind = "research"; id++; return "research-job-$id" }
        override suspend fun plan(researchId: String, selected: Map<String, String>, form: TravelLiveForm): String { currentKind = "plan"; id++; return "plan-job-$id" }
        override suspend fun job(id: String): TravelLiveJob = gate?.await() ?: TravelLiveJob(id, currentKind, "done", "完成", if (currentKind == "research") research else planResult, null)
        override suspend fun image(path: String): ByteArray? = null
    }
    private fun model(gateway: Gateway, saves: suspend (TravelLiveTrip) -> Long = { 10 }) = TravelLiveViewModel(application, SavedStateHandle(), { gateway }, saves, 1).also { store.put("vm-${System.nanoTime()}", it) }
    @Test fun stoppingPollingRetainsOriginalTaskAndBlocksInputReplacement() = runTest(dispatcher) {
        val gateway = Gateway(research()); gateway.gate = CompletableDeferred()
        val model = model(gateway)
        model.search("测试城市"); runCurrent(); val originalId = model.state.value.activeJobId
        model.stopViewing(); runCurrent()
        assertFalse(model.state.value.busy); assertNotNull(originalId)
        model.search("其他城市"); runCurrent(); assertEquals(originalId, model.state.value.activeJobId)
        gateway.gate!!.complete(TravelLiveJob(originalId!!, "research", "done", "完成", research(), null))
        model.resumeJob(); advanceUntilIdle()
        assertEquals(TravelLivePhase.CANDIDATES, model.state.value.phase); assertNull(model.state.value.activeJobId)
    }
    @Test fun needsChangesKeepsChoicesAndNeverSavesUntilExplicitConfirmation() = runTest(dispatcher) {
        val gateway = Gateway(research()); var saves = 0
        val model = model(gateway) { saves++; 42 }
        model.search("测试城市"); advanceUntilIdle(); model.toggleCandidate("a"); model.markMust("a"); model.setForm(form)
        gateway.planResult = JSONObject().put("status", "needs_changes").put("unplaced", JSONArray().put(JSONObject().put("id", "a").put("title", "测试景点").put("reason", "时间不足")))
        model.generate(); advanceUntilIdle()
        assertEquals(mapOf("a" to "must"), model.state.value.selected); assertEquals("a", model.state.value.unplaced.single().id); assertEquals(0, saves)
        gateway.planResult = result(); model.generate(); advanceUntilIdle()
        assertEquals(TravelLivePhase.RESULT, model.state.value.phase); assertEquals(0, saves)
        var saved: Long? = null; model.saveTrip { saved = it }; advanceUntilIdle(); assertEquals(42L, saved); assertEquals(1, saves)
        model.saveTrip { saved = it }; advanceUntilIdle(); assertEquals(1, saves)
    }
    @Test fun saveCommitCannotBeCancelledByStopBackOrDoubleTap() = runTest(dispatcher) {
        val gateway = Gateway(research()); val gate = CompletableDeferred<Unit>(); var saves = 0
        val model = model(gateway) { saves++; gate.await(); 73L }
        model.search("测试城市"); advanceUntilIdle(); model.toggleCandidate("a"); model.setForm(form)
        gateway.planResult = result(); model.generate(); advanceUntilIdle()
        var saved: Long? = null
        model.saveTrip { saved = it }
        model.saveTrip { saved = it }
        assertTrue(model.state.value.saving)
        runCurrent(); assertEquals(1, saves)
        model.stopViewing(); model.back(); runCurrent()
        assertTrue(model.state.value.saving); assertTrue(model.state.value.busy)
        assertEquals(TravelLivePhase.RESULT, model.state.value.phase)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(73L, saved); assertEquals(73L, model.state.value.savedTripId)
        assertFalse(model.state.value.saving)
        model.saveTrip { saved = it }; advanceUntilIdle(); assertEquals(1, saves)
    }
    @Test fun sourceAndImageUrlsRejectCredentialedOrExternalLinks() {
        assertEquals("", TravelLiveJson.sourceUrl("https://xiaohongshu.com.evil.example/test"))
        assertEquals("", TravelLiveJson.sourceUrl("https://user:password@www.xiaohongshu.com/test"))
        assertNull(TravelLiveJson.imagePath("/api/images/abcdefghijkl?token=private"))
    }
}
