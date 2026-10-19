package com.example.myplan.quickadd

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.component.TimePickerDialog
import com.example.myplan.ui.theme.MyPlanTheme
import kotlinx.coroutines.*
import java.time.*

/** Small editor opened directly by a notification PendingIntent; no main-page navigation. */
class QuickCaptureActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val token = intent.getStringExtra("token") ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val record = QuickCaptureStore(this@QuickCaptureActivity).get(token) ?: return@launch
            if (record.status == "saved") {
                try {
                    val repo = PlanRepository(PlanDatabase.getInstance(this@QuickCaptureActivity))
                    if (repo.getByCreationToken(token) != null) {
                        com.example.myplan.notification.PlanActions(repo, AlarmScheduler(this@QuickCaptureActivity)).sync(record.planId)
                        QuickCaptureNotifications.showSaved(this@QuickCaptureActivity, record)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Saved notification already explains the missing alarm permission. */ }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        val token = intent.getStringExtra("token") ?: run { finish(); return }
        setContent {
            MyPlanTheme(this@QuickCaptureActivity) {
                var draft by remember { mutableStateOf<QuickDraft?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(token) {
                    try {
                        draft = withContext(Dispatchers.IO) {
                            val record = QuickCaptureStore(this@QuickCaptureActivity).get(token) ?: error("快记已过期，请重新记录")
                            check(record.status != "cancelled") { "这条快记已取消" }
                            val repo = PlanRepository(PlanDatabase.getInstance(this@QuickCaptureActivity))
                            if (record.planId != 0L) {
                                val plan = repo.getByCreationToken(token) ?: error("这个计划已删除")
                                val rem = repo.getRemindersByPlanId(plan.id).firstOrNull()
                                val once = plan.oneTimeAt?.takeIf { plan.repeatMode == "ONCE" }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                                record.draft.copy(title = plan.name, repeatMode = plan.repeatMode, customDays = plan.customDays,
                                    at = plan.oneTimeAt, hour = once?.hour ?: rem?.hour ?: 9, minute = once?.minute ?: rem?.minute ?: 0)
                            } else if (record.status == "entry") {
                                val time = ZonedDateTime.now().plusMinutes(10)
                                QuickDraft("", hour = time.hour, minute = time.minute, date = time.toLocalDate().toString())
                            } else record.draft
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "暂时无法打开快记" }
                }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.background) {
                    if (draft != null) CaptureForm(token, draft!!, onDone = { finish() })
                    else Column(Modifier.padding(24.dp)) {
                        Text(error ?: "正在读取快记…")
                        TextButton(onClick = { finish() }) { Text("关闭") }
                    }
                }
            }
        }
    }

    @Composable
    private fun CaptureForm(token: String, initial: QuickDraft, onDone: () -> Unit) {
        var title by rememberSaveable { mutableStateOf(initial.title) }
        var date by rememberSaveable { mutableStateOf(initial.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
            ?: initial.date ?: LocalDate.now().toString()) }
        var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
        var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
        var mode by rememberSaveable { mutableStateOf(initial.repeatMode) }
        var showTime by rememberSaveable { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf(initial.issue) }
        val scope = rememberCoroutineScope()
        Column(Modifier.heightIn(max = 640.dp).imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("快记", style = MaterialTheme.typography.headlineSmall)
            Text("把这件事记下来", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("要提醒的事") },
                placeholder = { Text("例如：取快递") }, modifier = Modifier.fillMaxWidth(), enabled = !saving, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "ONCE", { mode = "ONCE" }, { Text("仅这一次") }, enabled = !saving)
                FilterChip(mode == "EVERY_DAY", { mode = "EVERY_DAY" }, { Text("每天") }, enabled = !saving)
            }
            if (mode == "WEEKDAYS" || mode == "CUSTOM") Text(if (mode == "WEEKDAYS") "周一至周五" else "每周 ${initial.customDays}")
            if (mode == "ONCE") OutlinedButton(onClick = {
                val d = LocalDate.parse(date)
                DatePickerDialog(this@QuickCaptureActivity, { _, y, m, day -> date = LocalDate.of(y, m + 1, day).toString() },
                    d.year, d.monthValue - 1, d.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth(), enabled = !saving) { Text("日期：$date") }
            OutlinedButton(onClick = { showTime = true }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
                Text("时间：%02d:%02d".format(hour, minute))
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!AlarmScheduler(this@QuickCaptureActivity).canScheduleExactAlarms() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                TextButton(onClick = { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName"))) }) {
                    Text("允许闹钟和提醒")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDone, modifier = Modifier.weight(1f), enabled = !saving) { Text("暂不保存") }
                Button(onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val time = if (mode == "ONCE") QuickAddParser.resolve(LocalDate.parse(date), LocalTime.of(hour, minute))
                                ?: error("这个时间受夏令时影响，请重新选择") else null
                            val original = initial.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                            val at = if (mode == "ONCE" && original?.toLocalDate()?.toString() == date && original.hour == hour && original.minute == minute) initial.at
                                else time?.toInstant()?.toEpochMilli()
                            val draft = QuickDraft(title.trim(), mode, initial.customDays, at, hour, minute, date)
                            withContext(Dispatchers.IO) { QuickCapture.saveEditor(this@QuickCaptureActivity, token, draft) }
                            QuickCaptureNotifications.refreshEntry(this@QuickCaptureActivity)
                            onDone()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "保存失败，请重试" }
                        finally { saving = false }
                    }
                }, modifier = Modifier.weight(1f), enabled = !saving) { Text(if (saving) "保存中…" else "保存提醒") }
            }
        }
        if (showTime) TimePickerDialog(hour, minute, onConfirm = { h, m -> hour = h; minute = m; showTime = false },
            onDismiss = { showTime = false })
    }
}
