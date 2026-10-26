package com.example.myplan.assistant

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.assistant.api.AssistConfiguration
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.resetAppDatabases
import com.example.myplan.voice.PlanVoiceData
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AssistCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var app: Application
    private lateinit var db: PlanDatabase
    private var nextKey = 0
    private val models = mutableListOf<AssistCaptureViewModel>()
    private val roomExecutor = Executors.newSingleThreadExecutor()

    @Before fun setUp() = runBlocking {
        resetAppDatabases(); Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        AssistConfiguration(app).clear()
        db = Room.inMemoryDatabaseBuilder(app, PlanDatabase::class.java)
            .setQueryExecutor(roomExecutor).setTransactionExecutor(roomExecutor).build()
        PlanDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, db) }
        Unit
    }

    @After fun tearDown() = runBlocking {
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        store.clear()
        withTimeout(8000) { while (jobs.any { !it.isCompleted }) { dispatcher.scheduler.runCurrent(); delay(10) } }
        jobs.joinAll()
        withContext(Dispatchers.IO) { roomExecutor.submit { }.get() }
        resetAppDatabases(); roomExecutor.shutdownNow(); Dispatchers.resetMain()
        Unit
    }

    private fun capture(handle: SavedStateHandle = SavedStateHandle()) = AssistCaptureViewModel(app, handle)
        .also { store.put("capture:${nextKey++}", it); models += it }

    private suspend fun awaitSaved(vm: AssistCaptureViewModel) = withTimeout(8000) {
        while (vm.state.value.savedPlanId == null && (vm.state.value.saving || vm.state.value.busy)) {
            dispatcher.scheduler.runCurrent(); delay(10)
        }
        assertNotNull(vm.state.value.error, vm.state.value.savedPlanId)
    }

    @Test fun previewNeedsConfirmationAndSurvivesRestorationWithItsToken() = runBlocking {
        val handle = SavedStateHandle()
        val vm = capture(handle)
        vm.updateInput("明晚八点读书，专注二十五分钟")
        vm.analyze(); dispatcher.scheduler.runCurrent()
        val preview = vm.state.value.preview!!
        assertFalse(vm.state.value.usedAi)
        assertTrue(withContext(Dispatchers.IO) { db.planDao().getAllPlansSync().isEmpty() })
        val restored = capture(SavedStateHandle(mapOf("input" to handle.get<String>("input"),
            "preview" to handle.get<String>("preview"), "token" to handle.get<String>("token"))))
        assertEquals(preview, restored.state.value.preview)
        restored.confirm(); awaitSaved(restored)
        val saved = withContext(Dispatchers.IO) { db.planDao().getPlanById(restored.state.value.savedPlanId!!)!! }
        assertEquals(preview.oneTimeAt, saved.oneTimeAt)
        assertEquals(25, saved.suggestedFocusMinutes)
        assertEquals(preview.creationToken, saved.creationToken)
    }

    @Test fun replayAfterProcessInterruptionReturnsSamePlanWithoutExtraReward() = runBlocking {
        val time = ZonedDateTime.now().plusDays(1).withHour(20).withMinute(0)
        val preview = PlanVoiceData(name = "阅读", reminderTimes = listOf("20:00"), repeatMode = "ONCE",
            oneTimeAt = time.toInstant().toEpochMilli(), focusMinutes = 45, creationToken = "confirmed-once")
        fun handle() = SavedStateHandle(mapOf("preview" to PlanDraftCodec.encode(preview), "token" to "confirmed-once"))
        val first = capture(handle()); first.confirm(); awaitSaved(first)
        val replay = capture(handle()); replay.confirm(); awaitSaved(replay)
        assertEquals(first.state.value.savedPlanId, replay.state.value.savedPlanId)
        assertEquals(1, withContext(Dispatchers.IO) { db.planDao().getAllPlansSync().size })
        assertEquals(0, withContext(Dispatchers.IO) { db.creditDao().balance(time.toLocalDate().toString()) })
    }

    @Test fun offlineQuestionAndVoiceSupplementUseSameDraftWithoutSaving() = runBlocking {
        val vm = capture()
        vm.updateInput("读书"); vm.analyze(); dispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.question)
        assertNull(vm.state.value.preview)
        vm.acceptVoice("明晚八点")
        assertEquals("明晚八点", vm.state.value.reply)
        vm.analyze(); dispatcher.scheduler.runCurrent()
        assertEquals("ONCE", vm.state.value.preview!!.repeatMode)
        assertTrue(withContext(Dispatchers.IO) { db.planDao().getAllPlansSync().isEmpty() })
    }

    @Test fun leavingCaptureCancelsPendingAnalysisButRetainsTheInput() {
        val vm = capture()
        vm.updateInput("明晚八点阅读")
        vm.analyze()
        vm.cancelAnalysis()
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals("明晚八点阅读", vm.state.value.input)
        assertNull(vm.state.value.preview)
        vm.analyze(); dispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.preview)
    }
}
