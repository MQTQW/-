package com.example.myplan.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.ui.screen.planedit.PlanEditViewModel
import com.example.myplan.voice.PlanVoiceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftTest {
    private val store = ViewModelStore()
    private lateinit var viewModel: PlanEditViewModel

    @Before fun setUp() {
        resetAppDatabases()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PlanEditViewModel(ApplicationProvider.getApplicationContext<Application>())
        store.put("editor", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        resetAppDatabases()
    }

    @Test fun reloadingNewPlanKeepsUnsavedUserInput() {
        viewModel.loadPlan(null)
        viewModel.updateName("睡前阅读")
        viewModel.updateDescription("读完这一章，再休息")
        val draftBeforeRecreation = viewModel.uiState.value

        // A recreated editor invokes load again while retaining its ViewModel.
        viewModel.loadPlan(null)

        assertEquals("睡前阅读", viewModel.uiState.value.name)
        assertEquals("读完这一章，再休息", viewModel.uiState.value.description)
        assertTrue(viewModel.uiState.value.isDirty)
        assertFalse(viewModel.uiState.value.isEditMode)
        assertEquals(draftBeforeRecreation, viewModel.uiState.value)
    }

    @Test fun voiceDraftFillsCustomDaysAndMultipleTimesWithoutOverwritingUserEdits() {
        viewModel.loadPlan(null)
        val voiceDraft = PlanVoiceData(
            name = "读书二十分钟",
            reminderTimes = listOf("08:30", "20:15"),
            repeatMode = "CUSTOM",
            customDays = setOf(1, 3, 6),
            category = "学习",
        )

        viewModel.applyVoiceDraft(voiceDraft)

        val filled = viewModel.uiState.value
        assertEquals("读书二十分钟", filled.name)
        assertEquals("学习", filled.category)
        assertEquals("CUSTOM", filled.repeatMode)
        assertEquals(setOf(1, 3, 6), filled.customDays)
        assertEquals(listOf(8 to 30, 20 to 15), filled.reminders.map { it.hour to it.minute })
        assertTrue(filled.isDirty)

        viewModel.updateName("睡前阅读")
        viewModel.updateDescription("只读纸质书")
        viewModel.updateCategory("日常")
        viewModel.updateReminder(1, 21, 0)
        viewModel.toggleCustomDay(3)
        viewModel.toggleCustomDay(7)
        val userEditedDraft = viewModel.uiState.value

        // Returning to the editor must not reapply the original voice result.
        viewModel.applyVoiceDraft(voiceDraft)

        assertEquals(userEditedDraft, viewModel.uiState.value)
        assertEquals("睡前阅读", viewModel.uiState.value.name)
        assertEquals(setOf(1, 6, 7), viewModel.uiState.value.customDays)
        assertEquals(listOf(8 to 30, 21 to 0), viewModel.uiState.value.reminders.map { it.hour to it.minute })
    }
}
