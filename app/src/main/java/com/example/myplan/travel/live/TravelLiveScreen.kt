package com.example.myplan.travel.live

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myplan.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The native flow uses the user's choices; it never supplies a static recommendation pool. */
@Composable
fun TravelLiveScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: TravelLiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirmSave by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("sights") }
    var resultDay by rememberSaveable { mutableIntStateOf(0) }
    var imagePreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val canEdit = !state.busy && state.activeJobId == null
    LaunchedEffect(state.research?.id) {
        val candidates = state.research?.candidates.orEmpty()
        if (candidates.none { it.category == category } && candidates.isNotEmpty()) category = candidates.first().category
    }

    val back = {
        focus.clearFocus()
        if (state.saving) { /* Commit and its saved ID must complete together. */ }
        else if (settings) settings = false
        else {
            if (state.busy) viewModel.stopViewing()
            if (state.phase == TravelLivePhase.INPUT) onBack() else viewModel.back()
        }
    }
    BackHandler { back() }
    val openSource: (TravelSource) -> Unit = { source ->
        val safe = TravelLiveJson.sourceUrl(source.url)
        if (safe.isNotBlank()) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
            catch (_: Exception) { scope.launch { snackbar.showSnackbar("暂时无法打开原帖，请稍后再试。") } }
        } else scope.launch { snackbar.showSnackbar("这篇攻略没有可打开的原帖链接。") }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CompanionHeader(if (settings) "连接旅行服务" else when (state.phase) {
                TravelLivePhase.INPUT -> "发现旅行"
                TravelLivePhase.CANDIDATES -> "挑选旅行内容"
                TravelLivePhase.WISHLIST -> "我的想去清单"
                TravelLivePhase.CONDITIONS -> "出行条件"
                TravelLivePhase.RESULT -> "行程预览"
            }, onBack = back, trailing = {
                if (!settings) IconButton(onClick = { settings = true }, enabled = !state.busy) {
                    Icon(Icons.Rounded.Settings, "旅行服务连接设置")
                }
            })
        },
        bottomBar = {
            if (!settings) Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        when (state.phase) {
                            TravelLivePhase.INPUT -> Unit
                            TravelLivePhase.CANDIDATES -> CompanionButton(
                                "查看想去清单（${state.selected.size}）", viewModel::showWishlist,
                                Modifier.fillMaxWidth(), Icons.Rounded.Checklist,
                                enabled = state.selected.isNotEmpty() && canEdit)
                            TravelLivePhase.WISHLIST -> CompanionButton(
                                "补充出行条件", viewModel::showConditions, Modifier.fillMaxWidth(),
                                Icons.AutoMirrored.Rounded.ArrowForward, state.selected.isNotEmpty() && canEdit)
                            TravelLivePhase.CONDITIONS -> CompanionButton(
                                if (state.busy) "正在编排行程…" else "让 AI 安排这 ${state.selected.size} 项",
                                { focus.clearFocus(); viewModel.generate() }, Modifier.fillMaxWidth(),
                                Icons.Rounded.AutoAwesome, canEdit && state.selected.isNotEmpty())
                            TravelLivePhase.RESULT -> {
                                CompanionButton(if (state.savedTripId != null) "打开已保存行程" else "保存到我的行程", {
                                    val id = state.savedTripId
                                    if (id != null) onSaved(id) else confirmSave = true
                                }, Modifier.fillMaxWidth(), Icons.Rounded.BookmarkAdd, !state.busy && state.trip != null)
                                if (state.trip?.form?.undated == true) Text(
                                    "日期未定：先返回出行条件选定日期，再重新编排保存。",
                                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (settings) {
                ConnectionPane(state, viewModel::setConfig, viewModel::testConnection)
            } else {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(state.phase) { listState.scrollToItem(0) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.busy || state.activeJobId != null) item("task") {
                        TaskStatus(state, viewModel::stopViewing, viewModel::resumeJob)
                    }
                    state.error?.let { message -> item("error") {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton(onClick = viewModel::dismissError, modifier = Modifier.align(Alignment.End)) { Text("收起提示") }
                            }
                        }
                    } }
                    when (state.phase) {
                        TravelLivePhase.INPUT -> {
                            item("city") { CityInput(state, { settings = true }, { city -> focus.clearFocus(); viewModel.search(city) }) }
                        }
                        TravelLivePhase.CANDIDATES -> {
                            val research = state.research
                            item("intro") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LiveHeading("在${research?.destination.orEmpty()}，先挑喜欢的")
                                    Text("景点、美食和体验来自本次攻略。选好内容，再让 AI 排时间与顺序。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    CategorySelector(category, research?.candidates.orEmpty(), { category = it })
                                }
                            }
                            val candidates = research?.candidates.orEmpty().filter { it.category == category }
                            if (candidates.isEmpty()) item("empty") {
                                CompanionNote("这轮攻略没有提取到${categoryLabel(category)}。可以看看其他分类，或返回换个城市重新检索。")
                            }
                            items(candidates, key = { it.id }) { candidate ->
                                CandidateCard(candidate, state.selected.containsKey(candidate.id), canEdit,
                                    research?.sources.orEmpty(), viewModel::loadImage,
                                    { viewModel.toggleCandidate(candidate.id) }, openSource,
                                    { imagePreview = it to candidate.title })
                            }
                            if (research != null) item("research-notes") { ResearchNotes(research, openSource) }
                        }
                        TravelLivePhase.WISHLIST -> {
                            val candidates = state.research?.candidates.orEmpty().filter { it.id in state.selected }
                            item("wishlist-intro") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LiveHeading("确认想去与想吃的内容")
                                    Text("${candidates.size} 项已加入。标记“必选”，让 AI 优先保留；它不会固定游玩时间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = viewModel::back, enabled = !state.busy) { Text("继续挑选内容") }
                                }
                            }
                            if (candidates.isEmpty()) item("wishlist-empty") { CompanionNote("清单还是空的，返回挑几项想去或想吃的内容吧。") }
                            items(candidates, key = { it.id }) { candidate ->
                                WishlistRow(candidate, state.selected[candidate.id] == "must", canEdit,
                                    { viewModel.markMust(candidate.id) }, { viewModel.toggleCandidate(candidate.id) })
                            }
                        }
                        TravelLivePhase.CONDITIONS -> {
                            item("conditions") { ConditionsPane(state.form, canEdit, viewModel::setForm) }
                            if (state.unplaced.isNotEmpty()) item("unplaced") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LiveHeading("这些内容还没排进去")
                                    state.unplaced.forEach { Text("${it.title}：${it.reason}", color = MaterialTheme.colorScheme.error) }
                                    TextButton(onClick = viewModel::showWishlist, enabled = !state.busy) { Text("返回清单调整") }
                                }
                            }
                        }
                        TravelLivePhase.RESULT -> {
                            val trip = state.trip
                            if (trip == null) item("no-trip") { CompanionNote("还没有可查看的行程，请先选择内容并完成编排。") }
                            else {
                                item("trip-overview") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LiveHeading(trip.title)
                                        Text("${trip.form.destination} · ${trip.days.size} 天 · ${if (trip.form.undated) "日期未定" else trip.form.date}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("按你选的 ${state.selected.size} 项内容编排，保存前仍可返回调整条件。")
                                        if (trip.planningNote.isNotBlank()) Text(trip.planningNote, style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = viewModel::showConditions, enabled = !state.busy) { Text("调整条件后重新编排") }
                                        if (trip.warnings.isNotEmpty()) CompanionNote(trip.warnings.joinToString("\n"))
                                    }
                                }
                                item("day-selector") {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        trip.days.forEachIndexed { index, day -> FilterChip(selected = resultDay.coerceIn(0, trip.days.lastIndex) == index,
                                            onClick = { resultDay = index }, label = { Text("第 ${day.day} 天") }, modifier = Modifier.heightIn(min = 48.dp)) }
                                    }
                                }
                                val day = trip.days[resultDay.coerceIn(0, trip.days.lastIndex)]
                                item("day-heading") { Text("第 ${day.day} 天${day.date?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.titleMedium) }
                                if (day.stops.isEmpty()) item("day-empty") { CompanionNote("这一天暂时留白，可以保存后手动安排。") }
                                itemsIndexed(day.stops, key = { _, stop -> "${day.day}:${stop.id}" }) { index, stop ->
                                    ResultStop(stop, index > 0, trip.sources, openSource)
                                }
                                item("result-boundaries") { CompanionNote("地图位置与交通仅为查询时结果。出行前仍需核对预约、营业、交通与排队；保存行程不会自动创建提醒。") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("保存这份行程？") },
        text = { Text(if (state.trip?.form?.undated == true) "当前还没有出发日期。返回出行条件选择日期并重新编排后，即可保存为每日行程。"
            else "将新建一份本地行程，保留攻略出处、位置状态和交通提醒。已有行程不受影响，节点不会自动发提醒。") },
        confirmButton = { TextButton(onClick = {
            confirmSave = false
            if (state.trip?.form?.undated == true) viewModel.showConditions() else viewModel.saveTrip(onSaved)
        }, enabled = !state.busy) { Text(if (state.trip?.form?.undated == true) "去选日期" else "确认保存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("继续查看") } },
    )
    imagePreview?.let { (path, title) ->
        Dialog(onDismissRequest = { imagePreview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                Column {
                    CompanionHeader("攻略配图", onBack = { imagePreview = null })
                    PostImage(path, title, viewModel::loadImage, Modifier.weight(1f).fillMaxWidth(), ContentScale.Fit)
                    Text("攻略配图，非地点实拍确认", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LiveHeading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

@Composable
private fun CityInput(state: TravelLiveState, onSettings: () -> Unit, onSearch: (String) -> Unit) {
    var city by rememberSaveable(state.destination) { mutableStateOf(state.destination) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LiveHeading("下一站，你想去哪里？")
        Text("先找城市攻略，挑出你喜欢的地方与食物，最后交给 AI 安排行程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = city, onValueChange = { city = it.take(40) }, label = { Text("旅游城市") },
            placeholder = { Text("例如：杭州") }, leadingIcon = { Icon(Icons.Rounded.LocationCity, null) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (city.isNotBlank() && !state.busy && state.config.isConfigured) onSearch(city.trim()) }))
        CompanionButton("搜索城市攻略", { onSearch(city.trim()) }, Modifier.fillMaxWidth(), Icons.Rounded.Search,
            enabled = !state.busy && city.isNotBlank() && state.config.isConfigured)
        if (!state.config.isConfigured) CompanionNote("第一次使用需要连接电脑上的旅行服务。连接完成后，就可以在手机里挑选内容和安排行程。")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(state.connection, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSettings, enabled = !state.busy) { Text(if (state.config.isConfigured) "连接设置" else "开始连接") }
        }
        HorizontalDivider()
        Text("你来选内容，AI 来排顺序", style = MaterialTheme.typography.titleSmall)
        Text("景点、美食和体验都保留攻略出处。本轮最多读取 3 篇攻略；没有提取到的分类会留空。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.research != null) CompanionNote("重新搜索会建立新的候选清单。已经保存到“我的行程”的内容仍保留。")
    }
}

@Composable
private fun ConnectionPane(state: TravelLiveState, onSave: (TravelLiveConfig) -> Unit, onCheck: () -> Unit) {
    var address by rememberSaveable(state.config.baseUrl) { mutableStateOf(state.config.baseUrl) }
    var token by remember { mutableStateOf(state.config.token) }
    var showToken by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveHeading("连接一次，再用手机规划")
                Text("当前使用你电脑上的旅行服务。用 USB 连接手机，运行电脑端的“连接手机旅行服务”，再填入该工具显示的连接码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("连接码只用于本机服务；无需在手机填写 DeepSeek 或高德密钥。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { OutlinedTextField(address, { address = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("本机服务地址") },
            supportingText = { Text("USB 转发默认使用 http://127.0.0.1:8794") }, singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)) }
        item { OutlinedTextField(token, { token = it.trim().take(64) }, Modifier.fillMaxWidth(), label = { Text("连接码") },
            supportingText = { Text("粘贴电脑工具生成的 64 位连接码") }, singleLine = true, enabled = !state.busy,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showToken) "隐藏连接码" else "显示连接码") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)) }
        item { CompanionButton("保存连接设置", { focus.clearFocus(); onSave(TravelLiveConfig(address.trim(), token.trim())) }, Modifier.fillMaxWidth(),
            Icons.Rounded.Link, !state.busy && token.length == 64) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.connection, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onCheck, enabled = !state.busy && state.config.isConfigured, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("检测已保存的连接") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item { CompanionNote("电脑旅行服务和 USB 转发需要保持运行。更换电脑、服务重启或登录过期后，请重新连接或扫码登录小红书。") }
    }
}

@Composable
private fun TaskStatus(state: TravelLiveState, onStop: () -> Unit, onResume: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.progress.ifBlank { if (state.busy) "正在连接旅行服务…" else "有一项任务可以继续查看" }, style = MaterialTheme.typography.bodyMedium)
        if (state.activeJobId != null) {
            TextButton(onClick = if (state.busy) onStop else onResume) { Text(if (state.busy) "暂时停止查看" else "继续查看任务") }
            if (state.busy) Text("停止查看后，服务端可能仍会完成这次任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun categoryLabel(category: String) = when (category) { "food" -> "美食"; "experiences" -> "体验"; else -> "景点" }

@Composable
private fun CategorySelector(selected: String, candidates: List<TravelCandidate>, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("sights", "food", "experiences").forEach { category ->
            FilterChip(selected = selected == category, onClick = { onSelect(category) }, label = { Text("${categoryLabel(category)} ${candidates.count { it.category == category }}") }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun CandidateCard(candidate: TravelCandidate, selected: Boolean, enabled: Boolean, sources: List<TravelSource>,
    loadImage: suspend (String) -> ByteArray?, onToggle: () -> Unit, openSource: (TravelSource) -> Unit, onImage: (String) -> Unit) {
    var expanded by rememberSaveable(candidate.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            candidate.image?.let { path ->
                Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clickable { onImage(path) }.semantics { contentDescription = "放大${candidate.title}的攻略配图" }) {
                    PostImage(path, candidate.title, loadImage, Modifier.fillMaxSize())
                    FilledTonalIconButton(onClick = { onImage(path) }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Icon(Icons.Rounded.Fullscreen, "放大攻略配图") }
                }
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (candidate.image != null) Text("攻略配图，非地点实拍确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                if (candidate.region.isNotBlank()) Text(candidate.region, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(candidate.description, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                Text("建议停留 ${candidate.duration} 分钟 · ${if (candidate.locationStatus == "matched") "地图位置已返回" else "地点待核对"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceLinks(candidate.sourceIds, sources, openSource)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) { Text(if (expanded) "收起介绍" else "展开介绍") }
                    FilledTonalButton(onClick = onToggle, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = if (selected) "从想去清单移除${candidate.title}" else "加入想去：${candidate.title}"
                    }) {
                        Icon(if (selected) Icons.Rounded.Check else Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (selected) "已加入" else "加入想去")
                    }
                }
            }
        }
    }
}

private data class PostImageState(val bitmap: ImageBitmap? = null, val loading: Boolean = true)

@Composable
private fun PostImage(path: String, title: String, loadImage: suspend (String) -> ByteArray?, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val image by produceState(PostImageState(), path) {
        val bytes = try { loadImage(path) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val decoded = if (bytes == null) null else withContext(Dispatchers.Default) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Exception) { null }
        }
        value = PostImageState(decoded, false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (image.bitmap != null) Image(image.bitmap!!, "$title 的原帖攻略配图", Modifier.fillMaxSize(), contentScale = scale)
        else Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (image.loading) "正在读取攻略配图…" else "这张配图暂时无法显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!image.loading) Text("仍可查看介绍与攻略出处", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceLinks(ids: List<String>, sources: List<TravelSource>, openSource: (TravelSource) -> Unit) {
    val linked = sources.filter { it.id in ids }
    if (linked.isNotEmpty()) Column {
        linked.forEach { source ->
            TextButton(onClick = { openSource(source) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("原帖 · ${source.title}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (source.author.isNotBlank()) Text(source.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResearchNotes(research: TravelResearch, openSource: (TravelSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本轮攻略来源", style = MaterialTheme.typography.titleSmall)
        Text("读取了 ${research.sources.size} 篇攻略。${formatQueryTime(research.fetchedAt).let { if (it.isBlank()) "" else "检索于 $it。" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        research.sources.forEach { source ->
            TextButton(onClick = { openSource(source) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(source.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (source.author.isNotBlank()) Text(source.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
            }
        }
        if (research.warnings.isNotEmpty()) CompanionNote(research.warnings.joinToString("\n"))
    }
}

@Composable
private fun WishlistRow(candidate: TravelCandidate, must: Boolean, enabled: Boolean, onMust: () -> Unit, onRemove: () -> Unit) {
    CompanionCard {
        Text(candidate.title, style = MaterialTheme.typography.titleMedium)
        Text("${categoryLabel(candidate.category)} · 建议停留 ${candidate.duration} 分钟", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = must, onClick = onMust, enabled = enabled, label = { Text(if (must) "已标为必选" else "标为必选") },
                leadingIcon = { Icon(if (must) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "从想去清单移除${candidate.title}" }) { Text("移除") }
        }
    }
}

@Composable
private fun ConditionsPane(form: TravelLiveForm, enabled: Boolean, onChange: (TravelLiveForm) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LiveHeading("什么时候出发，玩几天？")
        Text("${form.destination} · AI 只安排你选中的内容。到达、返程和住宿未填写时，不会替你猜测。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("玩几天", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { days -> FilterChip(selected = form.days == days, onClick = { onChange(form.copy(days = days)) }, enabled = enabled,
                label = { Text("$days 天") }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("日期还没确定", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = form.undated, onCheckedChange = { onChange(form.copy(undated = it, date = form.date.ifBlank { LocalDate.now().toString() })) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "日期还没确定" })
        }
        if (!form.undated) OutlinedButton(onClick = {
            val initial = runCatching { LocalDate.parse(form.date) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, { _, year, month, day -> onChange(form.copy(date = LocalDate.of(year, month + 1, day).toString())) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("出发日期 · ${form.date.ifBlank { "请选择" }}")
        }
        else Text("可以先预览安排；保存到每日行程前需确定日期并重新编排。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LiveField("首日抵达时间（选填）", form.arrival, enabled, "例如 10:30", { onChange(form.copy(arrival = it.take(5))) })
        LiveField("末日返程时间（选填）", form.returnTime, enabled, "例如 18:00", { onChange(form.copy(returnTime = it.take(5))) })
        LiveField("住宿地点（选填）", form.hotel, enabled, "未定可以留空", { onChange(form.copy(hotel = it.take(200))) })
        Text("旅行节奏", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("舒缓", "适中", "紧凑"), form.pace, enabled) { onChange(form.copy(pace = it)) }
        Text("主要出行方式", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("公共交通", "步行", "自驾"), form.mode, enabled) { onChange(form.copy(mode = it)) }
        CompanionNote("填写的城市、已选内容和出行条件会发送给电脑旅行服务，再由 AI 编排。生成后先预览，确认保存后才写入本地行程。")
    }
}

@Composable
private fun LiveField(label: String, value: String, enabled: Boolean, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, enabled: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(selected = selected == it, onClick = { onChange(it) }, label = { Text(it) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}

@Composable
private fun ResultStop(stop: TravelLiveStop, hasPrevious: Boolean, sources: List<TravelSource>, openSource: (TravelSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stop.travelMinutes != null) CompanionNote("到这里的交通估计 ${stop.travelMinutes} 分钟${formatQueryTime(stop.travelCheckedAt).let { if (it.isBlank()) "" else "，查询于 $it" }}。仅供参考，请核对预留时间。")
        else if (hasPrevious) Text("交通待核对，预留间隔不代表实际耗时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompanionCard {
            Text("${stop.time} · 停留 ${stop.duration} 分钟", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stop.title, style = MaterialTheme.typography.titleMedium)
            if (stop.mustKeep) Text(if (stop.selectionId == null) "已填写的固定条件" else "你标记的必选", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (stop.locationStatus == "matched") "地图位置已返回" else "具体地点待核对", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stop.notes.isNotBlank()) Text(stop.notes, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            SourceLinks(stop.sourceIds, sources, openSource)
        }
    }
}

private fun formatQueryTime(value: String): String = try {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
} catch (_: Exception) { "" }
