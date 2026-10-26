package com.example.myplan.data

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.assistant.api.AssistConfiguration
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.repository.PlanStepRepository
import com.example.myplan.ui.screen.planedit.PlanEditViewModel
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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class PlanStepEditorTest {
    private lateinit var app: Application
    private val dispatcher = StandardTestDispatcher()
    private lateinit var roomExecutor: ExecutorService
    private val editors = mutableListOf<PlanEditViewModel>()
    private val nextQueryGate = AtomicReference<QueryGate?>(null)
    private var activeQueryGate: QueryGate? = null
    private lateinit var db: PlanDatabase
    private val store = ViewModelStore()
    private var nextKey = 0

    @Before fun setUp() {
        resetAppDatabases()
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        AssistConfiguration(app).clear()
        roomExecutor = Executors.newSingleThreadExecutor()
        // Editor tests do not start MyPlanApp's independent rollover loop, and each
        // owns a fresh database rather than clearing one under active observers.
        db = Room.inMemoryDatabaseBuilder(app, PlanDatabase::class.java)
            .setQueryExecutor { task ->
                val gate = nextQueryGate.getAndSet(null)
                roomExecutor.execute {
                    gate?.let {
                        it.entered.countDown()
                        check(it.release.await(5, TimeUnit.SECONDS)) { "Test did not release the pending Room query" }
                    }
                    task.run()
                }
            }
            .setTransactionExecutor(roomExecutor)
            .build()
        PlanDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
    }

    @After fun tearDown() = runBlocking {
        try {
            activeQueryGate?.release?.countDown()
            nextQueryGate.getAndSet(null)?.release?.countDown()
            val jobs = editors.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            store.clear()
            awaitCondition { jobs.all { it.isCompleted } }
            jobs.forEach { it.join() }
            dispatcher.scheduler.runCurrent()
            if (::roomExecutor.isInitialized) roomExecutor.submit { }.get(5, TimeUnit.SECONDS)
            resetAppDatabases()
        } finally {
            try {
                if (::roomExecutor.isInitialized) {
                    roomExecutor.shutdown()
                    check(roomExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "Room executor did not stop" }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private data class QueryGate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

    private fun editor(handle: SavedStateHandle = SavedStateHandle()) = PlanEditViewModel(app, handle).also {
        editors += it
        store.put("editor:${nextKey++}", it)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) {
        while (true) {
            dispatcher.scheduler.runCurrent()
            if (condition()) return@withTimeout
            // Let Room/Dispatchers.IO complete real work; do not advance past an
            // arbitrary delay and assume its callbacks have already been delivered.
            delay(1)
        }
    }

    private suspend fun awaitIdle(vm: PlanEditViewModel) = awaitCondition { !vm.uiState.value.busy }

    @Test fun unsavedStepCompletionAndFocusDoNotTouchDatabase() = runBlocking {
        val vm = editor()
        vm.loadPlan(null); vm.updateName("读书"); vm.addStep()
        val key = vm.uiState.value.steps.single().key
        vm.updateStepTitle(key, "阅读第一节")
        vm.toggleStepCompleted(key)
        assertEquals("先保存计划，再勾选步骤。", vm.uiState.value.message)
        var launched = false
        vm.startStepFocus(key) { _, _ -> launched = true }
        assertFalse(launched)
        assertEquals("先保存修改，再开始专注。", vm.uiState.value.message)
        assertTrue(withContext(Dispatchers.IO) { db.planDao().getAllPlansSync().isEmpty() })
    }

    @Test fun restoredEditorKeepsManualStepsAndCreationTokenWithoutReapplyingVoiceDraft() {
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.loadPlan(null); vm.updateName("我的读书计划"); vm.updateFocusMinutes(45); vm.addStep()
        val key = vm.uiState.value.steps.single().key
        vm.updateStepTitle(key, "做一页笔记"); vm.updateStepMinutes(key, 15)
        val before = vm.uiState.value
        val restored = editor(SavedStateHandle(mapOf("editorDraft" to handle.get<Bundle>("editorDraft"), "editorPlanId" to 0L)))
        restored.loadPlan(null)
        restored.applyVoiceDraft(PlanVoiceData(name = "不应覆盖"))
        assertEquals(before, restored.uiState.value)
        assertNotNull(restored.uiState.value.creationToken)
    }

    @Test fun missingAiConfigurationKeepsManualStepsAndExplainsHowToContinue() = runBlocking {
        val vm = editor()
        vm.loadPlan(null); vm.updateName("学习 Kotlin"); vm.addStep()
        val key = vm.uiState.value.steps.single().key
        vm.updateStepTitle(key, "写一个示例")
        vm.requestSplit()
        awaitCondition { !vm.uiState.value.isSplitting }
        assertTrue(vm.uiState.value.assistantError.orEmpty().contains("配置"))
        assertEquals("写一个示例", vm.uiState.value.steps.single().title)
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test fun suggestionsAppendOnlyAfterSelectionAndStayUnsaved() = runBlocking {
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.loadPlan(null); vm.updateName("学习 Kotlin"); vm.addStep()
        vm.updateStepTitle(vm.uiState.value.steps.single().key, "保留我的步骤")
        val bundle = Bundle(handle.get<Bundle>("editorDraft")!!).apply {
            putParcelableArrayList("suggestions", arrayListOf(
                Bundle().apply { putString("title", "阅读变量与类型"); putInt("minutes", 15); putBoolean("selected", true) },
                Bundle().apply { putString("title", "写一段练习"); putInt("minutes", 25); putBoolean("selected", true) }
            ))
        }
        val restored = editor(SavedStateHandle(mapOf("editorDraft" to bundle, "editorPlanId" to 0L)))
        restored.loadPlan(null)
        assertEquals(listOf("保留我的步骤"), restored.uiState.value.steps.map { it.title })
        restored.toggleSuggestion(1)
        restored.updateSuggestion(0, "先读一页")
        restored.acceptSuggestions()
        assertEquals(listOf("保留我的步骤", "先读一页"), restored.uiState.value.steps.map { it.title })
        assertTrue(restored.uiState.value.isDirty)
        assertTrue(restored.uiState.value.suggestions.isEmpty())
        assertTrue(withContext(Dispatchers.IO) { db.planDao().getAllPlansSync().isEmpty() })
    }

    @Test fun savedStepKeepsItsIdAndCompletionWithoutCompletingOrRewardingParent() = runBlocking {
        val vm = editor()
        vm.loadPlan(null); vm.updateName("阅读"); vm.addStep()
        vm.updateStepTitle(vm.uiState.value.steps.single().key, "阅读第一章")
        vm.save { }; awaitIdle(vm)
        val planId = vm.uiState.value.id
        val step = vm.uiState.value.steps.single()
        assertTrue(step.id > 0)
        vm.toggleStepCompleted(step.key); awaitIdle(vm)
        awaitCondition { vm.uiState.value.steps.single().isDone("EVERY_DAY") }
        assertFalse(withContext(Dispatchers.IO) { PlanRepository(db).getPlanById(planId)!!.isCompleted })
        assertEquals(0, withContext(Dispatchers.IO) { db.creditDao().balance(LocalDate.now().toString()) })
        vm.updateStepTitle(step.key, "阅读第一章并做笔记")
        vm.toggleStepCompleted(step.key)
        assertTrue(withContext(Dispatchers.IO) { PlanStepRepository(db).getSteps(planId).single().isDoneOn("EVERY_DAY", LocalDate.now().toString()) })
        vm.save { }; awaitIdle(vm)
        assertEquals(step.id, vm.uiState.value.steps.single().id)
        assertTrue(vm.uiState.value.steps.single().isDone("EVERY_DAY"))
        assertEquals(0, withContext(Dispatchers.IO) { db.creditDao().balance(LocalDate.now().toString()) })
    }

    @Test fun switchingToNewPlanCancelsAnInFlightOldPlanLoad() = runBlocking {
        val oldId = withContext(Dispatchers.IO) { PlanRepository(db).savePlanWithReminders(
            PlanEntity(name = "旧计划"), listOf(ReminderEntity(planId = 0, hour = 9, minute = 0)), listOf(PlanStepDraft(title = "旧步骤"))
        ).first }
        roomExecutor.submit { }.get(5, TimeUnit.SECONDS)
        val gate = QueryGate().also { activeQueryGate = it; nextQueryGate.set(it) }
        val vm = editor()
        vm.loadPlan(oldId)
        dispatcher.scheduler.runCurrent()
        assertTrue("Old plan query must be in flight before switching", gate.entered.await(5, TimeUnit.SECONDS))
        assertTrue(vm.uiState.value.isLoading)
        val oldLoadJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
        assertTrue(oldLoadJobs.isNotEmpty())
        vm.loadPlan(null); vm.updateName("新的草稿")
        gate.release.countDown()
        awaitCondition { oldLoadJobs.all { it.isCompleted } }
        roomExecutor.submit { }.get(5, TimeUnit.SECONDS)
        dispatcher.scheduler.runCurrent()
        assertEquals("新的草稿", vm.uiState.value.name)
        assertEquals(0L, vm.uiState.value.id)
        assertTrue(vm.uiState.value.steps.isEmpty())
    }

    @Test fun onceDraftUsesItsExactLocalDateTimeAndSuggestedFocusLength() {
        val vm = editor()
        vm.loadPlan(null)
        val date = ZonedDateTime.now().plusDays(2).withHour(20).withMinute(30).withSecond(0).withNano(0)
        vm.applyVoiceDraft(PlanVoiceData(name = "阅读", repeatMode = "ONCE", oneTimeAt = date.toInstant().toEpochMilli(), focusMinutes = 45, creationToken = "once-token"))
        val state = vm.uiState.value
        assertEquals(date.toLocalDate().toString(), state.oneTimeDate)
        assertEquals(20 to 30, state.reminders.single().let { it.hour to it.minute })
        assertEquals(45, state.suggestedFocusMinutes)
        assertEquals("once-token", state.creationToken)
    }
}
