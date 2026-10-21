package com.example.myplan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.View
import android.util.SizeF
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.example.myplan.MainActivity
import com.example.myplan.MyPlanApp
import com.example.myplan.R
import com.example.myplan.data.DailyRollover
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import com.example.myplan.quickadd.QuickCaptureActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PlanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = requestUpdate(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = requestUpdate(context)
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetUndoStore(context).clear(it) } }
    override fun onDisabled(context: Context) { (context.applicationContext as? MyPlanApp)?.refreshWidgetObservation() }

    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? MyPlanApp)?.refreshWidgetObservation()
        if (intent.action !in setOf(ACTION_REFRESH, ACTION_COMPLETE, ACTION_UNDO)) {
            super.onReceive(context, intent)
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                    if (intent.action != ACTION_REFRESH && id in widgetIds(context)) {
                        DailyRollover.ensureToday(context)
                        val repo = PlanRepository(PlanDatabase.getInstance(context))
                        val actions = PlanActions(repo, AlarmScheduler(context))
                        val store = WidgetUndoStore(context)
                        val undo = store.get(id)
                        val isUndo = intent.action == ACTION_UNDO
                        val planId = intent.getLongExtra("plan_id", -1)
                        val version = intent.getLongExtra("updated_at", -1)
                        val date = intent.getStringExtra("date") ?: ""
                        if (!isUndo || undo == WidgetUndo(planId, version, date)) {
                            val changed = actions.setCompletedIfUnchanged(planId, !isUndo, date, version)
                            if (changed != null) {
                                if (isUndo) store.clear(id) else store.put(id, WidgetUndo(changed.id, changed.updatedAt, date))
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, if (isUndo) "已撤销完成" else "已完成：${changed.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    updateAll(context)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("PlanWidget", "Widget action failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "暂时无法更新，请打开应用检查", Toast.LENGTH_SHORT).show() }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.myplan.widget.REFRESH"
        const val ACTION_COMPLETE = "com.example.myplan.widget.COMPLETE"
        const val ACTION_UNDO = "com.example.myplan.widget.UNDO"
        private val updateMutex = Mutex()
        private fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlanWidgetProvider::class.java))
        fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

        fun requestUpdate(context: Context) {
            if (widgetIds(context).isNotEmpty()) context.sendBroadcast(Intent(context, PlanWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }

        suspend fun updateAll(context: Context) = updateMutex.withLock {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return@withLock
            DailyRollover.ensureToday(context)
            val repo = PlanRepository(PlanDatabase.getInstance(context))
            val plans = repo.observeAllPlans().first()
            val state = PlanWidgetState.from(plans, AlarmScheduler(context))
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                val undo = WidgetUndoStore(context).get(id)?.takeIf { u ->
                    u.date == LocalDate.now().toString() && plans.any { it.plan.id == u.planId && it.plan.isCompleted && it.plan.updatedAt == u.updatedAt }
                }
                val options = manager.getAppWidgetOptions(id)
                val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The host chooses the right height for portrait/landscape without waking us.
                    RemoteViews(listOf(110, 170).associate { height ->
                        SizeF(250f, height.toFloat()) to views(context, id, state, undo,
                            Bundle(options).apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height) })
                    })
                } else {
                    val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                    views(context, id, state, undo, Bundle(options).apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, options.getInt(heightKey, 200))
                    })
                }
                manager.updateAppWidget(id, remoteViews)
            }
        }

        internal fun views(context: Context, widgetId: Int, state: PlanWidgetState, undo: WidgetUndo?, options: Bundle = Bundle()): RemoteViews {
            val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200) < 170
            val views = RemoteViews(context.packageName, if (compact) R.layout.widget_daily_compact else R.layout.widget_daily)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val next = state.next
            val date = next?.let { Instant.ofEpochMilli(it.at).atZone(zone) }
            val plan = next?.row?.plan
            val notificationMissing = !NotificationManagerCompat.from(context).areNotificationsEnabled()
            val exactMissing = !AlarmScheduler(context).canScheduleExactAlarms()
            val header = if (notificationMissing) "通知权限待开启" else if (exactMissing) "提醒权限待开启" else "下一条提醒"
            views.setTextViewText(R.id.widget_heading, header)
            views.setTextViewText(R.id.widget_progress, "今天 ${state.completedCount} / ${state.todayCount} 已完成")
            views.setTextViewText(R.id.widget_time, date?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "")
            views.setViewVisibility(R.id.widget_time, if (date == null) View.GONE else View.VISIBLE)
            val title = plan?.name ?: when {
                state.pendingCount > 0 -> "今天还有 ${state.pendingCount} 项待办"
                state.todayCount > 0 -> "今天的计划已完成"
                else -> "还没有待提醒的计划"
            }
            views.setTextViewText(R.id.widget_title, title)
            val repeat = when (plan?.repeatMode) { "ONCE" -> "仅这一次"; "EVERY_DAY" -> "每天"; "WEEKDAYS" -> "工作日"; "CUSTOM" -> "每周 ${plan.customDays.replace(',', '、')}"; else -> "" }
            val dayLabel = when (date?.toLocalDate()) { null, today -> ""; today.plusDays(1) -> "明天 · "; else -> date.format(DateTimeFormatter.ofPattern("M月d日")) + " · " }
            views.setTextViewText(R.id.widget_subtitle, if (plan != null) dayLabel + repeat else if (state.pendingCount > 0) "打开查看尚未完成的计划" else "点右上角 ＋ 记下一件事")
            val add = Intent(context, QuickCaptureActivity::class.java).setAction(QuickCaptureActivity.ACTION_WIDGET_ADD)
                .setData("myplan://widget/$widgetId/add".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widget_add, activity(context, add))
            val detail = Intent(context, MainActivity::class.java).setData("myplan://widget/$widgetId/plan/${plan?.id ?: 0}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply { plan?.let { putExtra("plan_id", it.id) } }
            views.setOnClickPendingIntent(R.id.widget_content, activity(context, detail))
            views.setOnClickPendingIntent(R.id.widget_progress, activity(context, Intent(context, MainActivity::class.java)))
            if (notificationMissing || exactMissing) {
                val settings = if (notificationMissing) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM else Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData("package:${context.packageName}".toUri())
                views.setOnClickPendingIntent(R.id.widget_heading, activity(context, settings))
            } else views.setOnClickPendingIntent(R.id.widget_heading, activity(context, detail))
            if (compact && (notificationMissing || exactMissing)) {
                views.setTextViewText(R.id.widget_subtitle, header)
                val settings = if (notificationMissing) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM else Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData("package:${context.packageName}".toUri())
                views.setOnClickPendingIntent(R.id.widget_subtitle, activity(context, settings))
            }
            val completable = plan != null && date?.toLocalDate() == today && !plan.isCompleted
            views.setTextViewText(R.id.widget_complete, if (completable) if (plan!!.repeatMode == "ONCE") "✓  标记完成" else "✓  完成今天" else if (plan != null || state.pendingCount > 0) "查看计划" else "＋  新建计划")
            views.setContentDescription(R.id.widget_complete, if (completable) "完成：${plan!!.name}" else if (plan != null || state.pendingCount > 0) "查看计划" else "新建计划")
            views.setOnClickPendingIntent(R.id.widget_complete, if (completable) action(context, widgetId, ACTION_COMPLETE, plan!!.id, plan.updatedAt, today.toString())
                else activity(context, if (plan != null || state.pendingCount > 0) detail else add))
            views.setViewVisibility(R.id.widget_undo, if (undo == null) View.GONE else View.VISIBLE)
            if (undo != null) views.setOnClickPendingIntent(R.id.widget_undo, action(context, widgetId, ACTION_UNDO, undo.planId, undo.updatedAt, undo.date))
            // Launchers differ in cell dimensions; keep the title usable on narrow grids.
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
            views.setTextViewTextSize(R.id.widget_time, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact || width < 300) 32f else 40f)
            return views
        }

        private fun activity(context: Context, intent: Intent): PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun action(context: Context, widgetId: Int, action: String, planId: Long, version: Long, date: String): PendingIntent {
            val intent = Intent(context, PlanWidgetProvider::class.java).setAction(action)
                .setData("myplan://widget/$widgetId/$action/$planId/$version/$date".toUri())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("plan_id", planId)
                .putExtra("updated_at", version).putExtra("date", date)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
