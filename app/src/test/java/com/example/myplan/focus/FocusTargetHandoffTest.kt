package com.example.myplan.focus

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.MyPlanApp
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.resetAppDatabases
import com.example.myplan.focus.session.FocusSessionEntity
import com.example.myplan.focus.ui.home.HomeViewModel
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = MyPlanApp::class, sdk = [29])
class FocusTargetHandoffTest {
    private lateinit var app: MyPlanApp
    private lateinit var db: PlanDatabase
    private lateinit var plans: PlanRepository
    private val store = ViewModelStore()

    @Before fun setup() = runBlocking {
        resetAppDatabases()
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DailyRollover.ensureToday(app)
        db = PlanDatabase.getInstance(app)
        withContext(Dispatchers.IO) { db.clearAllTables(); app.focusDb.clearAllTables() }
        plans = PlanRepository(db)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }

    private suspend fun home(): HomeViewModel {
        val vm = HomeViewModel(app)
        store.put("focus", vm)
        withTimeout(5000) {
            while (!vm.state.value.focusDataLoaded || !vm.state.value.focusAppsLoaded) delay(10)
        }
        return vm
    }

    private suspend fun newSteppedPlan(): Long = PlanActions(plans, AlarmScheduler(app)).save(
        PlanEntity(name = "Read", suggestedFocusMinutes = 45), emptyList(),
        listOf(PlanStepDraft(title = "阅读知识点", focusMinutes = 15),
            PlanStepDraft(title = "写一个小例子", focusMinutes = 25)))

    @Test fun cancelledSessionAllowsFreshStepHandoffAndPreloadsItsActualDetails() = runBlocking {
        val oldPlan = plans.insertPlan(PlanEntity(name = "旧计划"))
        val oldSession = app.focusSessionRepository.start(oldPlan, 25, setOf("video.app"))
        app.focusSessionRepository.cancel(oldSession.sessionId)
        val vm = home()
        // Create after observation has started, as in saving an AI-generated plan then opening Focus.
        val id = newSteppedPlan()
        val steps = db.planStepDao().getSteps(id)
        db.planStepDao().setCompleted(id, steps.first().id, true, LocalDate.now().toString())
        assertTrue(vm.prepareFocusTarget(id, steps.last().id))
        withTimeout(5000) {
            while (vm.state.value.focusPlans.none { it.id == id } ||
                vm.state.value.focusSteps.none { it.id == steps.last().id }) delay(10)
        }
        val state = vm.state.value
        val restored = requireNotNull(state.focusSession)
        assertEquals(FocusSessionEntity.CANCELLED, restored.status)
        assertEquals(oldSession.sessionId, restored.sessionId)
        assertNull(state.focusMessage)
        assertEquals(45, state.focusPlans.single { it.id == id }.suggestedFocusMinutes)
        val selected = state.focusSteps.single { it.id == steps.last().id }
        assertEquals("写一个小例子", selected.title)
        assertEquals(25, selected.focusMinutes)
        assertFalse(selected.isDoneOn("EVERY_DAY", state.focusDate))
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(state.focusDate))
    }

    @Test fun unfinishedSessionRejectsIncomingStepWithoutReplacingItOrMutatingCompletion() = runBlocking {
        val oldPlan = plans.insertPlan(PlanEntity(name = "当前专注"))
        val current = app.focusSessionRepository.start(oldPlan, 25, setOf("video.app"))
        val id = newSteppedPlan()
        val step = db.planStepDao().getSteps(id).last()
        val vm = home()
        assertFalse(vm.prepareFocusTarget(id, step.id))
        assertTrue(vm.state.value.focusMessage.orEmpty().contains("已有一段专注"))
        assertEquals(current.sessionId, vm.state.value.focusSession!!.sessionId)
        assertEquals(FocusSessionEntity.RUNNING, app.focusSessionRepository.current()!!.status)
        assertFalse(db.planStepDao().getStep(id, step.id)!!.isCompleted)
        assertFalse(plans.getPlanById(id)!!.isCompleted)
        assertEquals(0, db.creditDao().balance(LocalDate.now().toString()))
    }
}
