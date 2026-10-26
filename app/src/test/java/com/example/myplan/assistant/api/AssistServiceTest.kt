package com.example.myplan.assistant.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AssistServiceTest {
    private val now = ZonedDateTime.parse("2026-10-26T23:58:00+08:00[Asia/Shanghai]")
    private val token = "private-access-code-at-least-32-chars"
    private val config = AssistConfig("https://assist.example.com/v1/assist", token)
    private fun response(date: Any = "2026-10-27", time: String = "00:05", mode: String = "ONCE", days: JSONArray = JSONArray(), question: Any = JSONObject.NULL) =
        JSONObject().put("draft", JSONObject().put("title", "读书").put("category", "学习").put("repeatMode", mode)
            .put("date", date).put("reminderTimes", JSONArray().put(time)).put("customDays", days).put("focusMinutes", 25))
            .put("question", question)

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected strict validation") }
        catch (e: AssistException) { assertEquals(AssistError.INVALID_RESPONSE, e.reason) }
    }

    @Test fun nextDayDraftUsesLocalDateAndRequiresNoEpochFromModel() {
        val parsed = AssistJson.parse(response().toString(), now)
        val draft = parsed.draft!!
        assertEquals(LocalDate.of(2026, 10, 27), draft.date)
        assertEquals(listOf(LocalTime.of(0, 5)), draft.reminderTimes)
        assertEquals(AssistRepeatMode.ONCE, draft.repeatMode)
        assertNull(parsed.question)
    }

    @Test fun invalidDatesTimesUnsupportedRepeatsAndPastOneTimeAreRejected() {
        expectInvalid { AssistJson.parse(response(date = "2026-02-30").toString(), now) }
        expectInvalid { AssistJson.parse(response(time = "24:00").toString(), now) }
        expectInvalid { AssistJson.parse(response(mode = "MONTHLY").toString(), now) }
        expectInvalid { AssistJson.parse(response(date = "2026-10-26", time = "23:57").toString(), now) }
        expectInvalid { AssistJson.parse(response(mode = "CUSTOM", date = JSONObject.NULL).toString(), now) }
        expectInvalid { AssistJson.parse(response(mode = "EVERY_DAY", date = JSONObject.NULL, days = JSONArray().put(1)).toString(), now) }
    }

    @Test fun daylightSavingsGapAndStrictFieldTypesAreRejected() {
        val gapNow = ZonedDateTime.parse("2027-03-27T20:00:00+01:00[Europe/Berlin]")
        expectInvalid { AssistJson.parse(response(date = "2027-03-28", time = "02:30").toString(), gapNow) }
        val wrong = response(mode = "CUSTOM", date = JSONObject.NULL, days = JSONArray().put("1"))
        expectInvalid { AssistJson.parse(wrong.toString(), now) }
        val extra = response().put("isSaved", true)
        expectInvalid { AssistJson.parse(extra.toString(), now) }
        val tooMany = response().apply { getJSONObject("draft").put("reminderTimes", JSONArray((0..8).map { "0$it:00" })) }
        expectInvalid { AssistJson.parse(tooMany.toString(), now) }
    }

    @Test fun missingDetailsMustAskBeforeBecomingUsable() {
        val partial = response(date = JSONObject.NULL, question = "想在哪一天提醒？")
        assertNotNull(AssistJson.parse(partial.toString(), now).question)
        expectInvalid { AssistJson.parse(partial.put("question", JSONObject.NULL).toString(), now) }
        assertNull(AssistJson.parse("""{"draft":null,"question":"可以改成每周指定星期吗？"}""", now).draft)
        expectInvalid { AssistJson.parse("""{"draft":null,"question":null}""", now) }
    }

    @Test fun splitRejectsDuplicatesOutOfRangeDurationAndMetadata() {
        val step = JSONObject().put("title", "阅读一个知识点").put("focusMinutes", 15)
        assertEquals(15, AssistJson.steps(JSONObject().put("steps", JSONArray().put(step)).toString()).single().focusMinutes)
        expectInvalid { AssistJson.steps(JSONObject().put("steps", JSONArray().put(step).put(step)).toString()) }
        expectInvalid { AssistJson.steps("""{"steps":[{"title":"阅读","focusMinutes":30}]}""") }
        expectInvalid { AssistJson.steps("""{"steps":[],"saved":true}""") }
    }

    @Test fun endpointRequiresHttpsInReleaseAndContainsNoCredentialsOrQuery() {
        assertEquals("https", config.validatedUrl(debug = false).scheme)
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")) {
            val local = AssistConfig("http://$host:8787/v1/assist", token)
            assertEquals("http", local.validatedUrl(debug = true).scheme)
            try { local.validatedUrl(debug = false); fail() } catch (e: AssistException) { assertEquals(AssistError.INVALID_CONFIGURATION, e.reason) }
        }
        for (url in listOf("http://example.com/v1/assist", "https://u:p@example.com/v1/assist", "https://example.com/v1/assist?key=x", "https://example.com/chat/completions")) {
            try { AssistConfig(url, token).validatedUrl(debug = true); fail() } catch (e: AssistException) { assertEquals(AssistError.INVALID_CONFIGURATION, e.reason) }
        }
        assertFalse(config.toString().contains(token))
    }

    @Test fun settingsAreSeparateAndCanBeCleared() {
        val settings = AssistConfiguration(ApplicationProvider.getApplicationContext<Application>())
        settings.clear(); assertFalse(settings.load().isConfigured)
        settings.save(config.endpoint, token)
        assertEquals(config, AssistConfiguration(ApplicationProvider.getApplicationContext<Application>()).load())
        settings.clear(); assertFalse(settings.load().isConfigured)
    }

    @Test fun clientSendsTimezoneAndAccessCodeAndValidatesActualResponse() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer $token", request.header("Authorization"))
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("2026-10-26T23:58:00+08:00", body.getString("now"))
            assertEquals("Asia/Shanghai", body.getString("timeZone"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toString().toResponseBody()).build()
        }.build()
        val parsed = AssistService({ config }, client).parse("明天零点五分读书", now)
        assertEquals("读书", parsed.draft!!.title)
    }

    @Test fun serviceRejectsConfigurationBeforeNetworkAndSanitizesHttpErrors() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("secret provider body")
                .body("private provider key".toResponseBody()).build()
        }.build()
        try { AssistService({ AssistConfig() }, client).parse("读书", now); fail() }
        catch (e: AssistException) { assertEquals(AssistError.NOT_CONFIGURED, e.reason) }
        assertEquals(0, calls)
        try { AssistService({ config }, client).parse("读书", now); fail() }
        catch (e: AssistException) {
            assertEquals(AssistError.UNAUTHORIZED, e.reason)
            assertFalse(e.message!!.contains("provider"))
        }
    }

    @Test fun coroutineCancellationCancelsTheUnderlyingCall() = runBlocking {
        val entered = CompletableDeferred<okhttp3.Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.complete(chain.call())
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toString().toResponseBody()).build()
        }.build()
        val job = launch { AssistService({ config }, client).parse("明天读书", now) }
        val call = entered.await()
        try { job.cancelAndJoin(); assertTrue(call.isCanceled()) }
        finally { release.countDown() }
    }
}
