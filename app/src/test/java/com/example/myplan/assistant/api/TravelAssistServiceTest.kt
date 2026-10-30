package com.example.myplan.assistant.api

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TravelAssistServiceTest {
    private val request = TravelAssistRequest("测试城市", "旅行草稿", "2030-10-28", 2, "Asia/Shanghai", notes = "安排休息")
    private val config = AssistConfig("https://assist.example.com/v1/assist", "dummy-travel-access-code-at-least-32-characters")
    private fun stop(time: String = "09:00") = JSONObject().put("title", "区域散步").put("location", "测试城市中心区域")
        .put("startTime", time).put("durationMinutes", 60).put("notes", "时间为建议，请自行核实地点开放与交通信息。")
    private fun result(days: Int = 2) = JSONObject().put("days", JSONArray().apply {
        repeat(days) { index -> put(JSONObject().put("day", index + 1).put("stops", JSONArray().put(stop()))) }
    })
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid itinerary") }
        catch (e: AssistException) { assertEquals(AssistError.INVALID_RESPONSE, e.reason) }
    }

    @Test fun itineraryRequestUsesExistingPrivateEndpointAndKeepsDateTimeAsStrings() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val sent = chain.request()
            assertEquals("Bearer ${config.accessToken}", sent.header("Authorization"))
            val buffer = okio.Buffer(); sent.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("itinerary", body.getString("operation")); assertEquals("2030-10-28", body.getString("startDate"))
            assertEquals("Asia/Shanghai", body.getString("timeZoneId")); assertEquals(2, body.getInt("dayCount"))
            assertEquals("公共交通", body.getString("transport")); assertEquals("适中", body.getString("pace"))
            assertFalse(body.has("now"))
            Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result().toString().toResponseBody()).build()
        }.build()
        val parsed = TravelAssistService({ config }, client).generate(request)
        assertEquals(2, parsed.days.size)
        assertEquals("09:00", parsed.days.first().stops.single().startTime)
        assertEquals(60, parsed.days.first().stops.single().durationMinutes)
    }

    @Test fun dayCountSequenceAndStopCountAreStrict() {
        invalid { TravelAssistJson.parse(result(1).toString(), request) }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).put("day", 2) }.toString(), request) }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).put("day", "1") }.toString(), request) }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).put("stops", JSONArray()) }.toString(), request) }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).put("stops", JSONArray().apply { repeat(7) { put(stop()) } }) }.toString(), request) }
    }

    @Test fun timeDurationCoordinatesAndOverlappingSuggestionsAreRejected() {
        fun changed(key: String, value: Any) = result().apply { getJSONArray("days").getJSONObject(0).getJSONArray("stops").getJSONObject(0).put(key, value) }.toString()
        for (value in listOf("24:00", "9:00", "09:60")) invalid { TravelAssistJson.parse(changed("startTime", value), request) }
        for (value in listOf(0, 721, 1.5, "60")) invalid { TravelAssistJson.parse(changed("durationMinutes", value), request) }
        invalid { TravelAssistJson.parse(changed("latitude", 30.1), request) }
        invalid { TravelAssistJson.parse(changed("startTime", "23:30"), request) }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).getJSONArray("stops").put(stop("09:30")) }.toString(), request) }
    }

    @Test fun outputLengthLimitsAndRequiredNotesMatchTheServerContract() {
        for ((key, value) in listOf("title" to "x".repeat(121), "location" to "x".repeat(201), "notes" to "x".repeat(1001), "location" to "")) {
            val bad = result().apply { getJSONArray("days").getJSONObject(0).getJSONArray("stops").getJSONObject(0).put(key, value) }
            invalid { TravelAssistJson.parse(bad.toString(), request) }
        }
        invalid { TravelAssistJson.parse(result().apply { getJSONArray("days").getJSONObject(0).getJSONArray("stops").getJSONObject(0).remove("notes") }.toString(), request) }
    }

    @Test fun invalidInputFailsBeforeAnyNetworkRequest() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result().toString().toResponseBody()).build()
        }.build()
        for (bad in listOf(request.copy(dayCount = 0), request.copy(dayCount = 15), request.copy(startDate = "2030-02-30"),
            request.copy(startDate = "9999-12-31"), request.copy(title = "x".repeat(121)), request.copy(notes = "x".repeat(1001)),
            request.copy(timeZoneId = "Not/A_Zone"), request.copy(transport = "飞机"))) {
            try { TravelAssistService({ config }, client).generate(bad); fail() }
            catch (e: AssistException) { assertEquals(AssistError.INVALID_INPUT, e.reason) }
        }
        assertEquals(0, calls)
    }

    @Test fun fourteenDayDraftCanExceedOldParseResponseSizeButStillHasABound() = runBlocking {
        val longResult = result(14).apply { getJSONArray("days").let { days ->
            repeat(14) { day -> days.getJSONObject(day).put("stops", JSONArray().apply {
                repeat(6) { index -> put(stop("%02d:00".format(8 + index * 2)).put("notes", "建".repeat(1000))) }
            }) }
        } }.toString()
        assertTrue(longResult.toByteArray().size > 32 * 1024)
        var body = longResult
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        assertEquals(14, TravelAssistService({ config }, client).generate(request.copy(dayCount = 14)).days.size)
        body = " ".repeat(TravelAssistService.MAX_RESPONSE_BYTES.toInt() + 1)
        try { TravelAssistService({ config }, client).generate(request.copy(dayCount = 14)); fail() }
        catch (e: AssistException) { assertEquals(AssistError.INVALID_RESPONSE, e.reason) }
    }

    @Test fun httpErrorsNeverExposeProviderResponseText() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("secret provider message")
                .body("secret provider body".toResponseBody()).build()
        }.build()
        try { TravelAssistService({ config }, client).generate(request); fail() }
        catch (e: AssistException) {
            assertEquals(AssistError.UNAUTHORIZED, e.reason); assertFalse(e.userMessage.contains("secret"))
        }
    }

    @Test fun cancellingTravelGenerationCancelsTheExistingHttpCall() = runBlocking {
        val entered = CompletableDeferred<okhttp3.Call>(); val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.complete(chain.call()); release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result().toString().toResponseBody()).build()
        }.build()
        val job = launch { TravelAssistService({ config }, client).generate(request) }
        val call = entered.await()
        try { job.cancelAndJoin(); assertTrue(call.isCanceled()) } finally { release.countDown() }
    }
}
