package com.example.myplan.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.repository.PlanStepRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PlanStepTest {
    private lateinit var db: PlanDatabase
    private lateinit var plans: PlanRepository
    private lateinit var steps: PlanStepRepository
    private val today = "2026-10-26"
    private val tomorrow = "2026-10-27"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlanDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanRepository(db)
        steps = PlanStepRepository(db)
    }

    @After fun close() { db.close() }

    private suspend fun plan(repeatMode: String = "EVERY_DAY"): Long = plans.savePlanWithReminders(
        PlanEntity(name = "学习 Kotlin", repeatMode = repeatMode, oneTimeAt = if (repeatMode == "ONCE") 1_000L else null),
        if (repeatMode == "ONCE") listOf(ReminderEntity(planId = 0, hour = 9, minute = 0)) else emptyList(),
        listOf(PlanStepDraft(title = "阅读一个知识点", focusMinutes = 15), PlanStepDraft(title = "写一个小例子"))
    ).first

    @Test fun togglingStepsChangesOnlyTheirProgressAndNeverAwardsCredits() = runBlocking {
        val id = plan()
        val parent = plans.getPlanById(id)!!
        val step = steps.getSteps(id).first()
        assertTrue(steps.setCompleted(id, step.id, true, today))
        assertTrue(steps.observe(id).first().first().isDoneOn(parent.repeatMode, today))
        assertEquals(parent, plans.getPlanById(id))
        assertEquals(0, db.creditDao().balance(today))
        assertNull(db.dailyCheckinDao().getByDate(today))
        assertTrue(db.completionDao().getAll().none { it.completed })
        assertTrue(steps.setCompleted(id, step.id, false, today))
        assertNull(steps.getSteps(id).first().completedDate)
        assertFalse(steps.getSteps(id).first().isCompleted)
        assertEquals(parent.updatedAt, plans.getPlanById(id)!!.updatedAt)
    }

    @Test fun dailyProgressResetsByDateWhileOnceProgressSurvivesMidnight() = runBlocking {
        val dailyId = plan()
        val onceId = plan("ONCE")
        steps.setCompleted(dailyId, steps.getSteps(dailyId).first().id, true, today)
        steps.setCompleted(onceId, steps.getSteps(onceId).first().id, true, today)
        assertFalse(steps.getSteps(dailyId).first().isDoneOn("EVERY_DAY", tomorrow))
        assertTrue(steps.getSteps(onceId).first().isDoneOn("ONCE", tomorrow))
        steps.setCompleted(dailyId, steps.getSteps(dailyId).first().id, true, tomorrow)
        assertEquals(tomorrow, steps.getSteps(dailyId).first().completedDate)
        assertEquals(0, db.creditDao().balance(tomorrow))
    }

    @Test fun completedOrUnrelatedParentsCannotChangeStepProgress() = runBlocking {
        val first = plan()
        val second = plan()
        val step = steps.getSteps(first).first()
        assertFalse(steps.setCompleted(second, step.id, true, today))
        plans.setCompleted(first, true, today)
        assertFalse(steps.setCompleted(first, step.id, true, today))
        assertFalse(steps.getSteps(first).first().isCompleted)
        assertEquals(1, db.creditDao().balance(today))
        assertFalse(steps.setCompleted(Long.MAX_VALUE, step.id, true, today))
    }

    @Test fun reorderedEditsKeepStableIdsAndCompletionButNewStepsStartPending() = runBlocking {
        val id = plan()
        val old = steps.getSteps(id)
        steps.setCompleted(id, old[0].id, true, today)
        val parent = plans.getPlanById(id)!!.copy(updatedAt = Long.MAX_VALUE - 1)
        plans.savePlanWithReminders(parent, emptyList(), listOf(
            PlanStepDraft(old[1].id, "修改后的例子", 45),
            PlanStepDraft(old[0].id, "  阅读  ", 15),
            PlanStepDraft(title = "整理问题")
        ))
        val edited = steps.getSteps(id)
        assertEquals(listOf(old[1].id, old[0].id), edited.take(2).map { it.id })
        assertEquals(listOf(0, 1, 2), edited.map { it.position })
        assertTrue(edited[1].isDoneOn("EVERY_DAY", today))
        assertEquals("阅读", edited[1].title)
        assertFalse(edited[2].isCompleted)
        assertFalse(edited[2].id in old.map { it.id })
        assertEquals(Long.MAX_VALUE - 1, plans.getPlanById(id)!!.updatedAt)
        val revision = plans.getPlanById(id)!!.updatedAt
        plans.savePlanWithReminders(plans.getPlanById(id)!!, emptyList())
        assertEquals(revision + 1, plans.getPlanById(id)!!.updatedAt)
    }

    @Test fun omittedStepsArePreservedAndExplicitEmptyListDeletesThem() = runBlocking {
        val id = plan()
        val original = steps.getSteps(id)
        plans.savePlanWithReminders(plans.getPlanById(id)!!.copy(name = "只改名称"), emptyList())
        assertEquals(original, steps.getSteps(id))
        plans.savePlanWithReminders(plans.getPlanById(id)!!, emptyList(), emptyList())
        assertTrue(steps.getSteps(id).isEmpty())
    }

    @Test fun stepWriteFailureRollsBackPlanRemindersAndProgressTogether() = runBlocking {
        val id = plan()
        val parent = plans.getPlanById(id)!!
        val original = steps.getSteps(id)
        steps.setCompleted(id, original.first().id, true, today)
        val before = steps.getSteps(id)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_step BEFORE INSERT ON plan_steps
            WHEN NEW.title = '写入失败' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END""")
        try {
            plans.savePlanWithReminders(parent.copy(name = "不应保存"),
                listOf(ReminderEntity(planId = id, hour = 20, minute = 30)),
                listOf(PlanStepDraft(original.first().id, "写入失败")))
            fail("Expected the injected step failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(parent, plans.getPlanById(id))
        assertTrue(plans.getRemindersByPlanId(id).isEmpty())
        assertEquals(before, steps.getSteps(id))
        assertEquals(0, db.creditDao().balance(today))
    }

    @Test fun invalidOrForeignDraftsCannotOverwriteExistingData() = runBlocking {
        val id = plan()
        val other = plan()
        val parent = plans.getPlanById(id)!!
        val original = steps.getSteps(id)
        val invalid = listOf(
            List(9) { PlanStepDraft(title = "步骤 $it") },
            listOf(PlanStepDraft(title = " ")),
            listOf(PlanStepDraft(title = "字".repeat(121))),
            listOf(PlanStepDraft(title = "时长", focusMinutes = 0)),
            listOf(PlanStepDraft(steps.getSteps(other).first().id, "别人的步骤")),
            listOf(PlanStepDraft(original.first().id, "A"), PlanStepDraft(original.first().id, "B"))
        )
        invalid.forEach { drafts ->
            try {
                plans.savePlanWithReminders(parent.copy(name = "不应保存"), emptyList(), drafts)
                fail("Invalid steps must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(parent, plans.getPlanById(id))
        assertEquals(original, steps.getSteps(id))
    }

    @Test fun deletingParentCascadesStepsAndPreventsStaleToggle() = runBlocking {
        val id = plan()
        val step = steps.getSteps(id).first()
        plans.deletePlan(plans.getPlanById(id)!!)
        assertTrue(steps.getSteps(id).isEmpty())
        assertFalse(steps.setCompleted(id, step.id, true, today))
    }

    @Test fun backupRetainsFocusSuggestionAndOrderedStepProgress() = runBlocking {
        val id = plan()
        val original = steps.getSteps(id)
        steps.setCompleted(id, original[1].id, true, today)
        plans.savePlanWithReminders(plans.getPlanById(id)!!.copy(suggestedFocusMinutes = 45), emptyList())
        val backup = JSONObject(PlanBackup.export(db))
        assertEquals(3, backup.getInt("version"))
        val exported = backup.getJSONArray("plans").getJSONObject(0)
        assertEquals(45, exported.getInt("suggestedFocusMinutes"))
        val saved = exported.getJSONArray("steps")
        assertEquals(2, saved.length())
        assertEquals(original[0].id, saved.getJSONObject(0).getLong("id"))
        assertEquals(15, saved.getJSONObject(0).getInt("focusMinutes"))
        assertEquals(today, saved.getJSONObject(1).getString("completedDate"))
        assertTrue(saved.getJSONObject(1).getBoolean("completed"))
    }
}
