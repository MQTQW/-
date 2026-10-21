package com.example.myplan.widget

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.R
import com.example.myplan.data.isScheduledOn
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.local.relation.PlanWithReminders
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.resetAppDatabases
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PlanWidgetTest {
    private lateinit var context: Context
    private lateinit var repo: PlanRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var oldZone: TimeZone
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 21) // Wednesday
    private val now get() = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() {
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone(zone))
        resetAppDatabases()
        context = ApplicationProvider.getApplicationContext()
        repo = PlanRepository(PlanDatabase.getInstance(context)); scheduler = AlarmScheduler(context)
        context.getSharedPreferences("widget_undo", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { resetAppDatabases(); TimeZone.setDefault(oldZone) }
    private fun row(id: Long, hour: Int = 18, minute: Int = 30, done: Boolean = false, mode: String = "EVERY_DAY",
        days: String = "", at: Long? = null, enabled: Boolean = true) = PlanWithReminders(
        PlanEntity(id=id, name="计划$id", isCompleted=done, repeatMode=mode, customDays=days, oneTimeAt=at, isEnabled=enabled),
        listOf(ReminderEntity(id=id, planId=id, hour=hour, minute=minute)))

    @Test fun progressCountsPlansInsteadOfReminderTimesAndChoosesNearestTime() {
        val multi=row(1).let { it.copy(reminders=it.reminders+ReminderEntity(id=20,planId=1,hour=19,minute=0)) }
        val state=PlanWidgetState.from(listOf(multi,row(2,hour=20,done=true),row(3,hour=17)),scheduler,now)
        assertEquals(3,state.todayCount); assertEquals(1,state.completedCount); assertEquals(2,state.pendingCount)
        assertEquals(1L,state.next!!.row.plan.id)
    }
    @Test fun completedRepeatMovesToTomorrowWithoutReofferingToday() {
        val state=PlanWidgetState.from(listOf(row(1,done=true)),scheduler,now)
        assertEquals(today.plusDays(1),Instant.ofEpochMilli(state.next!!.at).atZone(zone).toLocalDate())
        assertEquals(1,state.completedCount)
    }
    @Test fun pausedPlansAndDisabledRemindersAreExcluded() {
        val disabled=row(2).let { it.copy(reminders=it.reminders.map { r -> r.copy(isEnabled=false) }) }
        val state=PlanWidgetState.from(listOf(row(1,enabled=false),disabled),scheduler,now)
        assertEquals(0,state.todayCount); assertNull(state.next)
    }
    @Test fun customWeekdaysAndLegacyWeeklyMatchTheHomeList() {
        assertFalse(isScheduledOn(row(1,mode="CUSTOM",days="1,5"),today,zone))
        assertTrue(isScheduledOn(row(2,mode="WEEKDAYS"),today,zone))
        val legacy=row(3).let { it.copy(reminders=it.reminders.map { r -> r.copy(legacyRepeatMode="WEEKLY",legacyTime=now) }) }
        assertTrue(isScheduledOn(legacy,today,zone)); assertFalse(isScheduledOn(legacy,today.plusDays(1),zone))
    }
    @Test fun overdueOnceRemainsPendingButNeverBecomesANewFutureAlarm() {
        val state=PlanWidgetState.from(listOf(row(1,mode="ONCE",at=now-86_400_000)),scheduler,now)
        assertEquals(1,state.pendingCount); assertNull(state.next)
    }
    @Test fun tomorrowOnceDoesNotCountAsTodaysTask() {
        val state=PlanWidgetState.from(listOf(row(1,mode="ONCE",at=now+86_400_000)),scheduler,now)
        assertEquals(0,state.todayCount); assertNotNull(state.next)
    }
    @Test fun emptyStateHasNoSyntheticPlans() {
        val state=PlanWidgetState.from(emptyList(),scheduler,now)
        assertNull(state.next); assertEquals(0,state.todayCount)
        val view=PlanWidgetProvider.views(context,7,state,null).apply(context,null)
        assertEquals("还没有待提醒的计划",view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("今天 0 / 0 已完成",view.findViewById<TextView>(R.id.widget_progress).text.toString())
    }
    @Test fun longTitleIsBoundedAndNarrowWidgetKeepsReadableTime() {
        val state=PlanWidgetState.from(listOf(row(1).let { it.copy(plan=it.plan.copy(name="非常长的计划名称".repeat(30))) }),scheduler,now)
        val options=Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,260) }
        val view=PlanWidgetProvider.views(context,7,state,null,options).apply(context,null)
        assertEquals(2,view.findViewById<TextView>(R.id.widget_title).maxLines)
        assertEquals(32f*context.resources.displayMetrics.scaledDensity,view.findViewById<TextView>(R.id.widget_time).textSize,.1f)
    }
    @Test fun shortLauncherCellsUseCompactLayoutWithIndependentActions() {
        val state=PlanWidgetState.from(listOf(row(1)),scheduler,now)
        val options=Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,110) }
        val view=PlanWidgetProvider.views(context,7,state,WidgetUndo(1,2,LocalDate.now().toString()),options).apply(context,null)
        assertEquals(1,view.findViewById<TextView>(R.id.widget_title).maxLines)
        assertEquals(android.view.View.VISIBLE,view.findViewById<TextView>(R.id.widget_add).visibility)
        assertEquals(android.view.View.VISIBLE,view.findViewById<TextView>(R.id.widget_undo).visibility)
    }
    @Test fun repeatedCompletionAndUndoDoNotDuplicateCreditsOrDeletePlan() = runBlocking {
        val id=repo.savePlanWithReminders(PlanEntity(name="喝水"),listOf(ReminderEntity(planId=0,hour=9,minute=0))).first
        val actions=PlanActions(repo,scheduler); val date=LocalDate.now().toString(); val before=repo.getPlanById(id)!!
        val completed=actions.setCompletedIfUnchanged(id,true,date,before.updatedAt)!!
        assertNull(actions.setCompletedIfUnchanged(id,true,date,before.updatedAt))
        assertEquals(1,PlanDatabase.getInstance(context).completionDao().getAll().size)
        assertNotNull(actions.setCompletedIfUnchanged(id,false,date,completed.updatedAt))
        assertFalse(repo.getPlanById(id)!!.isCompleted); assertEquals("EVERY_DAY",repo.getPlanById(id)!!.repeatMode)
        assertEquals(0,PlanDatabase.getInstance(context).dailyCheckinDao().getByDate(date)!!.completedCount)
    }
    @Test fun staleWidgetCannotCompleteEditedPausedDeletedOrAnotherDaysPlan() = runBlocking {
        val id=repo.savePlanWithReminders(PlanEntity(name="喝水",updatedAt=100),listOf(ReminderEntity(planId=0,hour=9,minute=0))).first
        val actions=PlanActions(repo,scheduler); val date=LocalDate.now().toString()
        repo.updatePlan(repo.getPlanById(id)!!.copy(name="已编辑",updatedAt=101))
        assertNull(actions.setCompletedIfUnchanged(id,true,date,100))
        assertNull(actions.setCompletedIfUnchanged(id,true,LocalDate.now().minusDays(1).toString(),101))
        repo.updatePlan(repo.getPlanById(id)!!.copy(isEnabled=false))
        assertNull(actions.setCompletedIfUnchanged(id,true,date,101))
        repo.deletePlan(repo.getPlanById(id)!!)
        assertNull(actions.setCompletedIfUnchanged(id,true,date,101))
    }
    @Test fun undoCannotOverwriteASubsequentEdit() = runBlocking {
        val id=repo.savePlanWithReminders(PlanEntity(name="喝水"),listOf(ReminderEntity(planId=0,hour=9,minute=0))).first
        val actions=PlanActions(repo,scheduler); val date=LocalDate.now().toString()
        val done=actions.setCompletedIfUnchanged(id,true,date,repo.getPlanById(id)!!.updatedAt)!!
        repo.updatePlan(done.copy(name="新名称",updatedAt=done.updatedAt+1))
        assertNull(actions.setCompletedIfUnchanged(id,false,date,done.updatedAt))
        assertTrue(repo.getPlanById(id)!!.isCompleted)
    }
    @Test fun undoStateSurvivesProcessRecreationAndIsScopedPerWidget() {
        val undo=WidgetUndo(8,123,"2026-10-21")
        WidgetUndoStore(context).put(7,undo)
        assertEquals(undo,WidgetUndoStore(context).get(7)); assertNull(WidgetUndoStore(context).get(9))
        WidgetUndoStore(context).clear(7); assertNull(WidgetUndoStore(context).get(7))
    }
}
