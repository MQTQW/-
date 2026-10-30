package com.example.myplan.travel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.assistant.api.*
import com.example.myplan.travel.data.*
import com.example.myplan.travel.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TravelUiViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val viewModels = mutableListOf<ViewModel>()
    private val deferredResults = mutableListOf<CompletableDeferred<TravelAssistResult>>()
    private lateinit var app: Application
    private lateinit var db: TravelDatabase
    private lateinit var repo: TravelRepository
    private lateinit var executor: ExecutorService
    private var nextKey = 0

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        AssistConfiguration(app).clear()
        executor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(app, TravelDatabase::class.java)
            .setQueryExecutor(executor).setTransactionExecutor(executor).build()
        TravelDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        repo = TravelRepository(db)
    }
    @After fun tearDown() = runBlocking {
        try {
            deferredResults.forEach { it.complete(TravelAssistResult(emptyList())) }
            val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            store.clear()
            await { jobs.all { it.isCompleted } }
            jobs.forEach { it.join() }
            executor.submit { }.get(5, TimeUnit.SECONDS)
            db.close()
            TravelDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        } finally {
            try { executor.shutdown(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
            finally { Dispatchers.resetMain() }
        }
    }
    private fun <T : ViewModel> own(vm: T): T = vm.also { viewModels += it; store.put("travel:${nextKey++}", it) }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
        while (true) { dispatcher.scheduler.runCurrent(); if (condition()) return@withTimeout; delay(1) }
    }
    private fun trip(date: String = LocalDate.now().plusDays(3).toString()) = TripEntity(title = "杭州慢游", destination = "杭州", startDate = date, dayCount = 2, timeZoneId = "Asia/Shanghai")
    private suspend fun seed(stops: List<TripStopDraft> = emptyList(), date: String = LocalDate.now().plusDays(3).toString()): Long = repo.saveTrip(trip(date), stops)
    private fun result() = TravelAssistResult(listOf(
        TravelAssistDay(1, listOf(TravelAssistStop("湖边散步", "西湖", "09:00", 60, "预留休息时间"), TravelAssistStop("午餐", "湖滨", "12:00", 60))),
        TravelAssistDay(2, listOf(TravelAssistStop("逛博物馆", "浙江省博物馆", "10:00", 90)))
    ))
    private suspend fun detail(id: Long, handle: SavedStateHandle = SavedStateHandle(), generate: (suspend (TravelAssistRequest) -> TravelAssistResult)? = { result() }): TravelDetailViewModel {
        val vm = own(TravelDetailViewModel(app, handle, generate))
        vm.load(id); await { !vm.state.value.loading }
        assertNotNull(vm.state.value.data)
        return vm
    }

    @Test fun homeDraftRestoresWithoutSavingOrOverwritingUserInput() = runBlocking {
        val handle = SavedStateHandle()
        val vm = own(TravelHomeViewModel(app, handle))
        vm.create(); vm.change(vm.state.value.draft!!.copy(title = "我的杭州行程", destination = "杭州", dayCount = "4", notes = "带长辈，安排舒缓"))
        val restored = own(TravelHomeViewModel(app, SavedStateHandle(mapOf("trip_draft" to handle.get<String>("trip_draft")))))
        await { !restored.state.value.loading }
        assertEquals(vm.state.value.draft, restored.state.value.draft)
        assertTrue(restored.state.value.trips.isEmpty())
        assertTrue(repo.observeTrips().first().isEmpty())
    }

    @Test fun invalidBaseDraftDoesNotWriteTripAndManualSaveCreatesNoReminders() = runBlocking {
        val vm = own(TravelHomeViewModel(app))
        vm.create(); vm.change(vm.state.value.draft!!.copy(title = "杭州行程", destination = "杭州", dayCount = "15"))
        var id = 0L
        vm.save { id = it }
        assertEquals(0L, id)
        assertNotNull(vm.state.value.error)
        vm.change(vm.state.value.draft!!.copy(dayCount = "2"))
        vm.save { id = it }; await { !vm.state.value.busy }
        assertTrue(id > 0)
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
    }

    @Test fun databaseRefreshPreservesAnUnfinishedStopDraft() = runBlocking {
        val id = seed()
        val vm = detail(id)
        vm.addStop(); vm.changeStop(vm.state.value.stopDraft!!.copy(title = "尚未保存的安排", notes = "先确认同行者想法"))
        repo.saveStop(id, TripStopDraft(dayIndex = 1, title = "另一个已保存节点", startMinute = 600))
        await { vm.state.value.data!!.stops.size == 1 }
        assertEquals("尚未保存的安排", vm.state.value.stopDraft!!.title)
        assertEquals("先确认同行者想法", vm.state.value.stopDraft!!.notes)
        assertEquals(1, repo.getTrip(id)!!.stops.size)
    }

    @Test fun missingAiConfigurationStillAllowsTheManualNodeLifecycle() = runBlocking {
        val id = seed()
        val vm = detail(id, generate = null)
        vm.requestSuggestions()
        assertTrue(vm.state.value.aiError.orEmpty().contains("配置"))
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
        vm.addStop()
        vm.changeStop(vm.state.value.stopDraft!!.copy(title = "自己安排的早餐", location = "酒店餐厅", startTime = "08:30", duration = "45"))
        vm.saveStop()
        await { !vm.state.value.busy && vm.state.value.data!!.stops.size == 1 }
        assertNull(vm.state.value.stopDraft)
        val saved = vm.state.value.data!!.stops.single()
        assertEquals(510, saved.startMinute)
        vm.complete(saved)
        await { !vm.state.value.busy && vm.state.value.data!!.stops.single().isCompleted }
        vm.deleteStop(vm.state.value.data!!.stops.single())
        await { !vm.state.value.busy && vm.state.value.data!!.stops.isEmpty() }
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
    }

    @Test fun aiPreviewDoesNotWriteUntilSelectedSuggestionsAreAdopted() = runBlocking {
        val id = seed(listOf(TripStopDraft(dayIndex = 0, title = "保留原安排", startMinute = 480)))
        val original = repo.getTrip(id)!!.stops.single().id
        val vm = detail(id)
        vm.requestSuggestions(); await { !vm.state.value.generating }
        assertEquals(3, vm.state.value.suggestions.size)
        assertEquals(1, repo.getTrip(id)!!.stops.size)
        vm.state.value.suggestions.filter { it.title != "湖边散步" }.forEach { vm.changeSuggestion(it.key, it.copy(selected = false)) }
        val selected = vm.state.value.suggestions.first { it.selected }
        vm.changeSuggestion(selected.key, selected.copy(title = "慢慢逛西湖"))
        vm.adoptSuggestions(append = true); await { !vm.state.value.busy }
        val saved = repo.getTrip(id)!!.stops
        assertEquals(listOf("保留原安排", "慢慢逛西湖"), saved.map { it.title })
        assertEquals(original, saved.first().id)
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test fun failedAppendKeepsOriginalNodesAndEditablePreview() = runBlocking {
        val id = seed((0 until 12).map { TripStopDraft(dayIndex = 0, position = it, title = "已有节点 $it", startMinute = 30 * it, durationMinutes = 20) })
        val vm = detail(id)
        vm.requestSuggestions(); await { !vm.state.value.generating }
        val first = vm.state.value.suggestions.first()
        vm.changeSuggestion(first.key, first.copy(title = "我修改后的建议"))
        vm.adoptSuggestions(append = true); await { !vm.state.value.busy }
        assertEquals(12, repo.getTrip(id)!!.stops.size)
        assertEquals("我修改后的建议", vm.state.value.suggestions.first().title)
        assertTrue(vm.state.value.error.orEmpty().contains("12"))
    }

    @Test fun cancelingGenerationRejectsEvenANonCooperativeLateResult() = runBlocking {
        val id = seed()
        val pending = CompletableDeferred<TravelAssistResult>().also(deferredResults::add)
        val vm = detail(id, generate = { withContext(NonCancellable) { pending.await() } })
        val oldChildren = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.requestSuggestions(); dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.generating)
        val aiJobs = vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in oldChildren }.toList()
        assertTrue(aiJobs.isNotEmpty())
        vm.cancelGeneration(); pending.complete(result())
        await { aiJobs.all { it.isCompleted } }
        assertFalse(vm.state.value.generating)
        assertTrue(vm.state.value.suggestions.isEmpty())
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
    }

    @Test fun remindersRejectPastNodesAndPassFutureTripTimeZoneToTheCaller() = runBlocking {
        val pastId = seed(listOf(TripStopDraft(dayIndex = 0, title = "过去节点", startMinute = 600)), LocalDate.now().minusDays(3).toString())
        val past = detail(pastId)
        var called = false
        past.withReminder(past.state.value.data!!.stops.single()) { _, _ -> called = true }
        assertFalse(called)
        assertTrue(past.state.value.error.orEmpty().contains("已经过去"))
        val futureId = seed(listOf(TripStopDraft(dayIndex = 0, title = "未来节点", startMinute = 600)))
        val future = detail(futureId)
        future.withReminder(future.state.value.data!!.stops.single()) { trip, stop ->
            assertEquals("Asia/Shanghai", trip.timeZoneId)
            assertEquals(futureId, stop.tripId)
            called = true
        }
        assertTrue(called)
    }

    @Test fun restoredAiPreviewRetainsUserEditsWithoutSavingAutomatically() = runBlocking {
        val id = seed()
        val handle = SavedStateHandle()
        val vm = detail(id, handle)
        vm.requestSuggestions(); await { !vm.state.value.generating }
        val first = vm.state.value.suggestions.first()
        vm.changeSuggestion(first.key, first.copy(notes = "留半小时拍照", selected = false))
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        val restored = detail(id, SavedStateHandle(snapshot))
        assertEquals(vm.state.value.suggestions, restored.state.value.suggestions)
        assertTrue(repo.getTrip(id)!!.stops.isEmpty())
    }
}
