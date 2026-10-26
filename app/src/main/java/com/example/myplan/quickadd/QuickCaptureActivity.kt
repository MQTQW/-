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
import com.example.myplan.MainActivity
import com.example.myplan.assistant.PlanDraftWorkflow
import com.example.myplan.assistant.api.AssistConfiguration
import com.example.myplan.assistant.api.AssistException
import com.example.myplan.assistant.api.AssistService
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.ui.component.TimePickerDialog
import com.example.myplan.ui.theme.MyPlanTheme
import kotlinx.coroutines.*
import java.time.*

/** Small editor opened directly by a notification PendingIntent; no main-page navigation. */
class QuickCaptureActivity : ComponentActivity() {
    private var organizeJob: Job? = null
    private var organizeRevision = 0L
    private var organizing by mutableStateOf(false)
    private var configurationRevision by mutableIntStateOf(0)

    private fun cancelOrganizing() {
        organizeRevision++
        organizeJob?.cancel()
        organizeJob = null
        organizing = false
    }

    override fun onPause() {
        cancelOrganizing()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        configurationRevision++
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
        val fromWidget = intent.action == ACTION_WIDGET_ADD
        val token = savedInstanceState?.getString("token") ?: intent.getStringExtra("token")
            ?: if (fromWidget) java.util.UUID.randomUUID().toString() else run { finish(); return }
        intent.putExtra("token", token)
        setContent {
            MyPlanTheme(this@QuickCaptureActivity) {
                var draft by remember { mutableStateOf<QuickDraft?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(token) {
                    try {
                        draft = withContext(Dispatchers.IO) {
                            if (fromWidget && QuickCaptureStore(this@QuickCaptureActivity).get(token) == null) {
                                QuickCaptureStore(this@QuickCaptureActivity).apply {
                                    prune()
                                    put(CaptureRecord(token, QuickDraft(""), status = "entry"))
                                }
                            }
                            val record = QuickCaptureStore(this@QuickCaptureActivity).get(token) ?: error("快记已过期，请重新记录")
                            check(record.status != "cancelled") { "这条快记已取消" }
                            val repo = PlanRepository(PlanDatabase.getInstance(this@QuickCaptureActivity))
                            if (record.planId != 0L) {
                                val plan = repo.getByCreationToken(token) ?: error("这个计划已删除")
                                val reminders = repo.getRemindersByPlanId(plan.id)
                                val rem = reminders.firstOrNull()
                                val once = plan.oneTimeAt?.takeIf { plan.repeatMode == "ONCE" }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                                record.draft.copy(title = plan.name, repeatMode = plan.repeatMode, customDays = plan.customDays,
                                    at = plan.oneTimeAt, hour = once?.hour ?: rem?.hour ?: 9, minute = once?.minute ?: rem?.minute ?: 0,
                                    category = plan.category, focusMinutes = plan.suggestedFocusMinutes,
                                    reminderTimes = reminders.map { "%02d:%02d".format(it.hour, it.minute) })
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("token", intent.getStringExtra("token"))
        super.onSaveInstanceState(outState)
    }

    companion object { const val ACTION_WIDGET_ADD = "com.example.myplan.WIDGET_ADD" }

    @Composable
    private fun CaptureForm(token: String, initial: QuickDraft, onDone: () -> Unit) {
        var sentence by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf(initial.title) }
        var date by rememberSaveable { mutableStateOf(initial.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
            ?: initial.date ?: LocalDate.now().toString()) }
        var times by rememberSaveable { mutableStateOf(initial.reminderTimes.ifEmpty { listOf("%02d:%02d".format(initial.hour, initial.minute)) }) }
        var mode by rememberSaveable { mutableStateOf(initial.repeatMode) }
        var days by rememberSaveable { mutableStateOf(initial.customDays) }
        var category by rememberSaveable { mutableStateOf(initial.category ?: "日常") }
        var focusMinutes by rememberSaveable { mutableIntStateOf(initial.focusMinutes ?: 25) }
        var editingTime by rememberSaveable { mutableIntStateOf(-2) }
        var saving by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf(initial.issue) }
        var messageIsError by remember { mutableStateOf(initial.issue != null) }
        val scope = rememberCoroutineScope()
        val configuration = remember { AssistConfiguration(this@QuickCaptureActivity) }
        val configured = remember(configurationRevision) { configuration.load().isConfigured }
        val workflow = remember { PlanDraftWorkflow(AssistService({ configuration.load() })::parse) }
        fun edited() { cancelOrganizing(); message = null }
        val done = { cancelOrganizing(); onDone() }
        DisposableEffect(Unit) { onDispose { cancelOrganizing() } }
        Column(Modifier.heightIn(max = 640.dp).imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (intent.action == ACTION_WIDGET_ADD) "新建提醒" else "快记", style = MaterialTheme.typography.headlineSmall)
            Text("先整理成草稿，确认后再保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(sentence, { edited(); sentence = it.take(700) }, label = { Text("一句话安排") },
                placeholder = { Text("明晚八点读书，专注二十五分钟") }, modifier = Modifier.fillMaxWidth(), enabled = !saving, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    cancelOrganizing()
                    val revision = organizeRevision
                    organizing = true
                    message = null
                    organizeJob = scope.launch {
                        try {
                            val result = workflow.parse(sentence, allowAi = configuration.load().isConfigured)
                            ensureActive()
                            if (revision != organizeRevision) return@launch
                            val plan = result.plan
                            if (plan != null) {
                                title = plan.name
                                mode = plan.repeatMode
                                days = plan.customDays.sorted().joinToString(",")
                                category = plan.category
                                times = plan.reminderTimes
                                plan.oneTimeAt?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
                                focusMinutes = plan.focusMinutes ?: focusMinutes
                                message = if (result.usedAi) "已用 AI 整理为草稿，请核对后保存。" else "已在本机整理为草稿，请核对后保存。"
                                messageIsError = false
                            } else {
                                message = result.question ?: "请补充事项和提醒时间。"
                                messageIsError = true
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: AssistException) { if (revision == organizeRevision) { message = e.userMessage; messageIsError = true } }
                        catch (e: IllegalArgumentException) { if (revision == organizeRevision) { message = e.message ?: "请补充具体事项和时间。"; messageIsError = true } }
                        catch (_: Exception) { if (revision == organizeRevision) { message = "暂时无法整理，请手动填写或稍后重试。"; messageIsError = true } }
                        finally { if (revision == organizeRevision) organizing = false }
                    }
                }, modifier = Modifier.weight(1f), enabled = !saving && !organizing && sentence.isNotBlank()) {
                    Text(if (organizing) "整理中…" else "智能整理")
                }
                if (organizing) TextButton(onClick = { cancelOrganizing() }, enabled = !saving) { Text("取消整理") }
            }
            if (!configured) {
                Text("简单表达可在本机整理；复杂表达可配置 AI 服务，也可直接手动填写。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    cancelOrganizing()
                    startActivity(Intent(this@QuickCaptureActivity, MainActivity::class.java).putExtra("open_ai_settings", true))
                }, enabled = !saving) { Text("配置 AI 服务") }
            }
            message?.let { Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            OutlinedTextField(title, { edited(); title = it.take(200) }, label = { Text("要提醒的事") },
                placeholder = { Text("例如：取快递") }, modifier = Modifier.fillMaxWidth(), enabled = !saving, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "ONCE", { edited(); mode = "ONCE"; times = times.take(1) }, { Text("仅这一次") }, enabled = !saving)
                FilterChip(mode == "EVERY_DAY", { edited(); mode = "EVERY_DAY" }, { Text("每天") }, enabled = !saving)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "WEEKDAYS", { edited(); mode = "WEEKDAYS" }, { Text("工作日") }, enabled = !saving)
                FilterChip(mode == "CUSTOM", { edited(); mode = "CUSTOM" }, { Text("指定星期") }, enabled = !saving)
            }
            if (mode == "CUSTOM") {
                listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { day ->
                            val selected = day in days.split(',').mapNotNull(String::toIntOrNull)
                            FilterChip(selected, {
                                edited()
                                val updated = days.split(',').mapNotNull(String::toIntOrNull).toMutableSet()
                                if (selected) updated.remove(day) else updated.add(day)
                                days = updated.sorted().joinToString(",")
                            }, { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]) }, enabled = !saving)
                        }
                    }
                }
            }
            if (mode == "ONCE") OutlinedButton(onClick = {
                edited()
                val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                DatePickerDialog(this@QuickCaptureActivity, { _, y, m, day -> date = LocalDate.of(y, m + 1, day).toString() },
                    d.year, d.monthValue - 1, d.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth(), enabled = !saving) { Text("日期：$date") }
            times.forEachIndexed { index, value ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edited(); editingTime = index }, modifier = Modifier.weight(1f), enabled = !saving) {
                        Text("提醒时间：$value")
                    }
                    if (times.size > 1) TextButton(onClick = { edited(); times = times.filterIndexed { i, _ -> i != index } }, enabled = !saving) { Text("移除") }
                }
            }
            if (mode != "ONCE" && times.size < 8) TextButton(onClick = { edited(); editingTime = -1 }, enabled = !saving) { Text("添加提醒时间") }
            Text("专注时长", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 25, 45).forEach { value ->
                    FilterChip(focusMinutes == value, { edited(); focusMinutes = value }, { Text("$value 分钟") }, enabled = !saving)
                }
            }
            Text("分类", style = MaterialTheme.typography.labelLarge)
            listOf(listOf("日常", "工作", "学习"), listOf("健康", "紧急", "其他")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { value -> FilterChip(category == value, { edited(); category = value }, { Text(value) }, enabled = !saving) }
                }
            }
            if (!AlarmScheduler(this@QuickCaptureActivity).canScheduleExactAlarms() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                TextButton(onClick = { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName"))) }) {
                    Text("允许闹钟和提醒")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = done, modifier = Modifier.weight(1f), enabled = !saving) { Text("暂不保存") }
                Button(onClick = {
                    cancelOrganizing()
                    saving = true
                    scope.launch {
                        try {
                            val selectedTime = LocalTime.parse(times.first())
                            val hour = selectedTime.hour
                            val minute = selectedTime.minute
                            val time = if (mode == "ONCE") QuickAddParser.resolve(LocalDate.parse(date), LocalTime.of(hour, minute))
                                ?: error("这个时间受夏令时影响，请重新选择") else null
                            val original = initial.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                            val at = if (mode == "ONCE" && original?.toLocalDate()?.toString() == date && original.hour == hour && original.minute == minute) initial.at
                                else time?.toInstant()?.toEpochMilli()
                            val draft = QuickDraft(title.trim(), mode, days, at, hour, minute, date,
                                focusMinutes = focusMinutes, category = category, reminderTimes = times)
                            withContext(Dispatchers.IO) { QuickCapture.saveEditor(this@QuickCaptureActivity, token, draft) }
                            QuickCaptureNotifications.refreshEntry(this@QuickCaptureActivity)
                            done()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "保存失败，请重试"; messageIsError = true }
                        finally { saving = false }
                    }
                }, modifier = Modifier.weight(1f), enabled = !saving) { Text(if (saving) "保存中…" else "保存提醒") }
            }
        }
        if (editingTime >= -1) {
            val time = times.getOrNull(editingTime)?.let { LocalTime.parse(it) } ?: LocalTime.of(9, 0)
            TimePickerDialog(time.hour, time.minute, onConfirm = { h, m ->
                edited()
                val updated = times.toMutableList()
                val value = "%02d:%02d".format(h, m)
                if (editingTime == -1) updated.add(value) else updated[editingTime] = value
                times = updated
                editingTime = -2
            }, onDismiss = { editingTime = -2 })
        }
    }
}
