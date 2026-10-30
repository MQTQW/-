package com.example.myplan.travel.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.travel.data.*
import com.example.myplan.ui.component.*
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TravelHomeScreen(onBack: () -> Unit, onOpenTrip: (Long) -> Unit, viewModel: TravelHomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.busy) { }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompanionHeader("旅行行程", subtitle = "把期待的旅程，一天一天安排好", onBack = { if (!state.busy) onBack() }) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                CompanionButton("新建行程", viewModel::create, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), Icons.Rounded.Add, !state.busy)
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.takeIf { state.draft == null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.loading && state.trips.isEmpty()) {
                CompanionCard {
                    Icon(Icons.Rounded.Luggage, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("下一段旅行，慢慢安排", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("先写目的地和日期，再把想去的地方排进每一天。手动规划可以离线使用。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.trips.forEach { row ->
                key(row.trip.id) {
                    CompanionCard(Modifier.clickable(enabled = !state.busy) { onOpenTrip(row.trip.id) }) {
                        Text(row.trip.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(row.trip.destination, style = MaterialTheme.typography.bodyLarge)
                        Text("${row.trip.startDate} 出发 · ${row.trip.dayCount} 天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("${row.stops.size} 个节点 · 已完成 ${row.stops.count { it.isCompleted }} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { viewModel.edit(row.trip) }, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) { Text("编辑基础信息") }
                    }
                }
            }
            CompanionNote("行程安排与日常计划分别管理。只有主动选择“另存为提醒”，节点才会进入提醒计划。")
        }
    }
    state.draft?.let { TripEditorDialog(it, state.busy, state.error, viewModel::change, viewModel::dismiss) { viewModel.save(onOpenTrip) } }
}

@Composable
fun TravelDetailScreen(
    tripId: Long, onBack: () -> Unit, onAiSettings: () -> Unit,
    onCreateReminder: (TripEntity, TripStopEntity) -> Unit,
    onOpenMap: (TripEntity, TripStopEntity) -> Unit,
    initialDay: Int? = null,
    viewModel: TravelDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(tripId) { viewModel.load(tripId) }
    var initialDayConsumed by rememberSaveable(tripId, initialDay) { mutableStateOf(initialDay == null) }
    LaunchedEffect(state.loading, state.data?.trip?.dayCount, initialDay) {
        val days = state.data?.trip?.dayCount
        if (!initialDayConsumed && !state.loading && days != null && initialDay != null) {
            viewModel.selectDay(initialDay.coerceIn(0, days - 1))
            initialDayConsumed = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.cancelGeneration() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.cancelGeneration() }
    }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { if (state.tripDraft == null && state.stopDraft == null && state.suggestions.isEmpty()) state.error?.let { snackbars.showSnackbar(it) } }
    var showMenu by remember { mutableStateOf(false) }
    var deleteTrip by rememberSaveable(tripId) { mutableStateOf(false) }
    var deleteStopId by rememberSaveable(tripId) { mutableStateOf<Long?>(null) }
    BackHandler(enabled = state.busy) { }
    val trip = state.data?.trip
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            CompanionHeader("行程安排", onBack = { if (!state.busy) { viewModel.cancelGeneration(); onBack() } }, trailing = {
                if (trip != null) Box {
                    IconButton(onClick = { showMenu = true }, enabled = !state.busy) { Icon(Icons.Rounded.MoreVert, "行程操作") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("编辑基础信息") }, onClick = { showMenu = false; viewModel.editTrip() })
                        DropdownMenuItem(text = { Text("删除行程", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; deleteTrip = true })
                    }
                }
            })
        }, bottomBar = {
            if (trip != null) Surface(color = MaterialTheme.colorScheme.background) {
                CompanionButton("添加行程节点", viewModel::addStop, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), Icons.Rounded.Add, !state.busy)
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loading && trip == null) CompanionNote("行程不存在或已被删除，可以返回行程列表重新选择。")
            if (trip != null) {
                CompanionCard {
                    Text(trip.title, style = MaterialTheme.typography.titleLarge)
                    Text(trip.destination, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("${trip.startDate} 出发 · ${trip.dayCount} 天", style = MaterialTheme.typography.bodyMedium)
                    Text("时区：${trip.timeZoneId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${trip.transport} · ${trip.pace}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (trip.notes.isNotBlank()) Text(trip.notes, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
                CompanionCard {
                    Text("AI 帮我安排", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("根据目的地、日期和节奏生成建议，先预览，再决定采纳哪些。仅发送这次行程的基础信息与备注。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    CompanionButton(if (state.generating) "正在整理行程…" else "生成行程建议", viewModel::requestSuggestions,
                        Modifier.fillMaxWidth(), Icons.Rounded.AutoAwesome, !state.busy && !state.generating)
                    if (state.generating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                        TextButton(onClick = viewModel::cancelGeneration, modifier = Modifier.align(Alignment.End)) { Text("取消生成") }
                    }
                    state.aiError?.let {
                        Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onAiSettings, enabled = !state.busy) { Text("配置 AI 服务") }
                    }
                }
                DaySelector(trip.dayCount, state.selectedDay, trip.startDate, viewModel::selectDay, !state.busy)
                val stops = state.data!!.stops.filter { it.dayIndex == state.selectedDay }
                val warnings = TripSchedule.conflicts(state.data!!.stops)
                if (warnings.isNotEmpty()) CompanionNote(warnings.take(4).joinToString("\n") + if (warnings.size > 4) "\n另有 ${warnings.size - 4} 处时间重叠，请检查其他日期。" else "")
                if (stops.zipWithNext().any { (a, b) -> a.startMinute > b.startMinute }) CompanionNote("节点顺序与开始时间不同。可以上下移动节点，或修改开始时间。")
                Text("第 ${state.selectedDay + 1} 天 · ${LocalDate.parse(trip.startDate).plusDays(state.selectedDay.toLong())}", style = MaterialTheme.typography.titleSmall)
                if (stops.isEmpty()) CompanionCard {
                    Text("这一天还很自由", style = MaterialTheme.typography.titleSmall)
                    Text("添加想去的地方，或从 AI 建议中挑几项。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                stops.forEachIndexed { index, stop ->
                    key(stop.id) {
                        TripStopCard(stop, index, stops.size, !state.busy,
                            onCompleted = { viewModel.complete(stop) }, onEdit = { viewModel.editStop(stop) },
                            onDelete = { deleteStopId = stop.id }, onMove = { viewModel.moveStop(stop, it) },
                            onMap = { onOpenMap(trip, stop) }, onReminder = { viewModel.withReminder(stop, onCreateReminder) })
                    }
                }
                CompanionNote("时间是计划安排，地点是可搜索的地名；地图中查看实时路线、营业及交通信息。节点完成仅记录行程进度，不产生积分。")
                Text("节点不会自动发提醒。另存为提醒后，提醒和行程独立管理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    state.tripDraft?.let { TripEditorDialog(it, state.busy, state.error, viewModel::changeTrip, viewModel::dismissTrip, viewModel::saveTrip) }
    if (trip != null) {
        state.stopDraft?.let { StopEditorDialog(it, trip, state.busy, state.error, viewModel::changeStop, viewModel::dismissStop, viewModel::saveStop) }
        if (state.suggestions.isNotEmpty()) TravelSuggestionsDialog(state, viewModel)
    }
    if (deleteTrip) AlertDialog(onDismissRequest = { if (!state.busy) deleteTrip = false }, title = { Text("删除整个行程？") },
        text = { Text("行程和其中所有节点都会删除。已经另存的提醒仍独立保留。") },
        confirmButton = { TextButton(onClick = { viewModel.deleteTrip { deleteTrip = false; onBack() } }, enabled = !state.busy) { Text("删除行程", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteTrip = false }, enabled = !state.busy) { Text("保留") } })
    deleteStopId?.let { id -> AlertDialog(onDismissRequest = { deleteStopId = null }, title = { Text("删除这个节点？") },
        text = { Text("删除不会影响已经另存的独立提醒。") },
        confirmButton = { TextButton(onClick = { state.data?.stops?.find { it.id == id }?.let(viewModel::deleteStop); deleteStopId = null }, enabled = !state.busy) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteStopId = null }) { Text("保留") } }) }
}

@Composable
private fun TripStopCard(stop: TripStopEntity, index: Int, count: Int, enabled: Boolean,
    onCompleted: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit, onMap: () -> Unit, onReminder: () -> Unit) {
    CompanionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${timeLabel(stop.startMinute)} — ${if (stop.startMinute + stop.durationMinutes >= 1440) "次日 " else ""}${timeLabel((stop.startMinute + stop.durationMinutes) % 1440)}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Checkbox(checked = stop.isCompleted, onCheckedChange = { onCompleted() }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "标记${stop.title}完成" })
        }
        Text(stop.title, style = MaterialTheme.typography.titleMedium)
        if (stop.location.isNotBlank()) Text(stop.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (stop.isCompleted) "已完成 · ${stop.durationMinutes} 分钟" else "预计停留 ${stop.durationMinutes} 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stop.notes.isNotBlank()) Text(stop.notes, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "上移节点") }
            IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "下移节点") }
            IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Rounded.Edit, "编辑节点") }
            IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Rounded.DeleteOutline, "删除节点") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMap, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("地图查看") }
            OutlinedButton(onClick = onReminder, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("另存为提醒") }
        }
    }
}

@Composable
internal fun DaySelector(count: Int, selected: Int, startDate: String, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { day -> FilterChip(selected = selected == day, onClick = { onSelect(day) }, enabled = enabled,
            label = { Text("第 ${day + 1} 天 · ${LocalDate.parse(startDate).plusDays(day.toLong()).monthValue}/${LocalDate.parse(startDate).plusDays(day.toLong()).dayOfMonth}") }) }
    }
}

private fun timeLabel(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun TripEditorDialog(draft: TripEditorDraft, busy: Boolean, error: String?, onChange: (TripEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(if (draft.id == 0L) "新建旅行行程" else "编辑行程基础") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TravelField("行程名称", draft.title, !busy) { onChange(draft.copy(title = it.take(120))) }
                TravelField("目的地", draft.destination, !busy) { onChange(draft.copy(destination = it.take(200))) }
                OutlinedButton(onClick = {
                    val date = runCatching { LocalDate.parse(draft.startDate) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(context, { _, y, m, d -> onChange(draft.copy(startDate = LocalDate.of(y, m + 1, d).toString())) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("出发日期：${draft.startDate}") }
                TravelField("天数", draft.dayCount, !busy, number = true) { onChange(draft.copy(dayCount = it.filter(Char::isDigit).take(2))) }
                Text("支持 1—14 天；减少天数前，请先移动或删除超出日期的节点。", style = MaterialTheme.typography.bodySmall)
                TravelField("旅行时区", draft.timeZoneId, !busy) { onChange(draft.copy(timeZoneId = it.take(80))) }
                Text("填写 IANA 时区，例如 Asia/Shanghai。节点按旅行时区保存，手机换时区后仍保持同一时刻。", style = MaterialTheme.typography.bodySmall)
                CommonTimeZones(draft.timeZoneId, !busy) { onChange(draft.copy(timeZoneId = it)) }
                TextButton(onClick = { onChange(draft.copy(timeZoneId = ZoneId.systemDefault().id)) }, enabled = !busy) { Text("使用手机时区") }
                TravelChoice("主要出行", listOf("公共交通", "步行", "自驾"), draft.transport, !busy) { onChange(draft.copy(transport = it)) }
                TravelChoice("行程节奏", listOf("舒缓", "适中", "紧凑"), draft.pace, !busy) { onChange(draft.copy(pace = it)) }
                TravelField("备注（选填）", draft.notes, !busy, multiline = true) { onChange(draft.copy(notes = it.take(1000))) }
                Text("可写饮食偏好、同行者需求，或想避开的安排。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text(if (busy) "正在保存…" else "保存行程") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun StopEditorDialog(draft: TripStopEditorDraft, trip: TripEntity, busy: Boolean, error: String?, onChange: (TripStopEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(if (draft.id == 0L) "添加行程节点" else "编辑行程节点") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DaySelector(trip.dayCount, draft.dayIndex, trip.startDate, { onChange(draft.copy(dayIndex = it, position = Int.MAX_VALUE)) }, !busy)
            StopFields(draft, !busy, onChange)
            Text("这里只保存行程节点，不会自动创建提醒。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text(if (busy) "正在保存…" else "保存节点") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun StopFields(draft: TripStopEditorDraft, enabled: Boolean, onChange: (TripStopEditorDraft) -> Unit) {
    TravelField("节点名称", draft.title, enabled) { onChange(draft.copy(title = it.take(120))) }
    TravelField("地点", draft.location, enabled) { onChange(draft.copy(location = it.take(200))) }
    Text("填写可在地图中搜索的地名，例如“西湖”。", style = MaterialTheme.typography.bodySmall)
    TravelField("开始时间", draft.startTime, enabled) { onChange(draft.copy(startTime = it.take(5))) }
    Text("使用 24 小时制，例如 09:30。", style = MaterialTheme.typography.bodySmall)
    TravelField("停留分钟", draft.duration, enabled, number = true) { onChange(draft.copy(duration = it.filter(Char::isDigit).take(3))) }
    Text("预计停留 1—720 分钟。", style = MaterialTheme.typography.bodySmall)
    TravelField("备注（选填）", draft.notes, enabled, multiline = true) { onChange(draft.copy(notes = it.take(1000))) }
}

@Composable
private fun TravelSuggestionsDialog(state: TravelDetailState, viewModel: TravelDetailViewModel) {
    val data = state.data ?: return
    val trip = data.trip
    var day by rememberSaveable { mutableIntStateOf(0) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    val selectedCount = state.suggestions.count { it.selected }
    if (!replacing) AlertDialog(onDismissRequest = { if (!state.busy) viewModel.dismissSuggestions() }, modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false), title = { Text("先看看这份行程建议") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("可修改内容、时间和停留时长，只采纳你勾选的节点。建议不是实时地图路线。", style = MaterialTheme.typography.bodySmall)
            DaySelector(trip.dayCount, day, trip.startDate, { day = it }, !state.busy)
            state.suggestions.filter { it.dayIndex == day }.forEach { suggestion -> key(suggestion.key) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = suggestion.selected, onCheckedChange = { viewModel.changeSuggestion(suggestion.key, suggestion.copy(selected = it)) }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "采纳${suggestion.title}" })
                    Text("采纳这个节点", style = MaterialTheme.typography.labelLarge)
                }
                StopFields(suggestion, !state.busy) { viewModel.changeSuggestion(suggestion.key, it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            } }
            if (state.suggestions.none { it.dayIndex == day }) Text("这一天没有建议节点。")
            Text("共勾选 $selectedCount 个节点，覆盖所有日期。", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = { viewModel.adoptSuggestions(append = true) }, enabled = !state.busy && selectedCount > 0) { Text(if (data.stops.isEmpty()) "采纳所选节点" else "追加所选节点") }
            if (data.stops.isNotEmpty()) TextButton(onClick = { replacing = true }, enabled = !state.busy && selectedCount > 0) { Text("替换整个行程节点") }
        } }, dismissButton = { TextButton(onClick = viewModel::dismissSuggestions, enabled = !state.busy) { Text("暂不采纳") } })
    if (replacing) AlertDialog(onDismissRequest = { if (!state.busy) replacing = false }, modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false), title = { Text("替换所有日期的节点？") },
        text = { Column { Text("现有节点及其完成进度都会被所选建议替换。已经另存的提醒不受影响，也不会自动更新。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = { viewModel.adoptSuggestions(append = false) }, enabled = !state.busy) { Text("确认替换") } },
        dismissButton = { TextButton(onClick = { replacing = false }, enabled = !state.busy) { Text("返回预览") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TravelChoice(label: String, options: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option -> FilterChip(selected = option == selected, onClick = { onSelect(option) }, enabled = enabled, label = { Text(option) }) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommonTimeZones(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val zones = listOf("中国" to "Asia/Shanghai", "日本" to "Asia/Tokyo", "法国" to "Europe/Paris", "纽约" to "America/New_York", "UTC" to "UTC")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        zones.forEach { (label, id) -> FilterChip(selected = id == selected, onClick = { onSelect(id) }, enabled = enabled, label = { Text(label) }) }
    }
}

@Composable
private fun TravelField(label: String, value: String, enabled: Boolean, number: Boolean = false, multiline: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        shape = RoundedCornerShape(14.dp), singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 4 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = if (multiline) ImeAction.Default else ImeAction.Next))
}
