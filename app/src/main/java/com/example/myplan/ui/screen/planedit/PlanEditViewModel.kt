package com.example.myplan.ui.screen.planedit

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.myplan.assistant.api.AssistConfiguration
import com.example.myplan.assistant.api.AssistException
import com.example.myplan.assistant.api.AssistService
import com.example.myplan.data.local.PlanDatabase
import com.example.myplan.data.local.entity.PlanEntity
import com.example.myplan.data.local.entity.PlanStepDraft
import com.example.myplan.data.local.entity.PlanStepEntity
import com.example.myplan.data.local.entity.ReminderEntity
import com.example.myplan.data.repository.PlanRepository
import com.example.myplan.data.repository.PlanStepRepository
import com.example.myplan.notification.AlarmScheduler
import com.example.myplan.notification.PlanActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ReminderState(
    val id: Long = 0,
    val hour: Int = 9,
    val minute: Int = 0,
    val isEnabled: Boolean = true,
) {
    fun timeText(): String = "%02d:%02d".format(hour, minute)
}

data class PlanStepEditState(
    val id: Long = 0,
    val title: String = "",
    val focusMinutes: Int = 25,
    val isCompleted: Boolean = false,
    val completedDate: String? = null,
    val key: String = UUID.randomUUID().toString(),
) {
    fun isDone(repeatMode: String, date: String = LocalDate.now().toString()): Boolean =
        isCompleted && (repeatMode == "ONCE" || completedDate == date)
}

data class StepSuggestionState(val title: String, val focusMinutes: Int = 25, val selected: Boolean = true)

data class PlanEditUiState(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val category: String = "日常",
    val isCompleted: Boolean = false,
    val isEnabled: Boolean = true,
    val repeatMode: String = "EVERY_DAY",
    val oneTimeZoneId: String = ZoneId.systemDefault().id,
    val oneTimeDate: String = LocalDate.now(ZoneId.of(oneTimeZoneId)).toString(),
    val originalOneTimeAt: Long? = null,
    val customDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val reminders: List<ReminderState> = emptyList(),
    val suggestedFocusMinutes: Int = 25,
    val creationToken: String? = UUID.randomUUID().toString(),
    val steps: List<PlanStepEditState> = emptyList(),
    val suggestions: List<StepSuggestionState> = emptyList(),
    val isSplitting: Boolean = false,
    val assistantError: String? = null,
    val message: String? = null,
    val isLoading: Boolean = false,
    val updatingStepKey: String? = null,
    val isEditMode: Boolean = false,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
) {
    val busy: Boolean get() = isSaving || isLoading || updatingStepKey != null
}

class PlanEditViewModel(
    application: Application,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
    private val db = PlanDatabase.getInstance(application)
    private val repo = PlanRepository(db)
    private val stepRepo = PlanStepRepository(db)
    private val scheduler = AlarmScheduler(application)
    private val assistant by lazy { AssistService({ AssistConfiguration(application).load() }) }
    private val _uiState = MutableStateFlow(restoreDraft(savedState["editorDraft"]) ?: PlanEditUiState())
    val uiState: StateFlow<PlanEditUiState> = _uiState.asStateFlow()
    private var loadedPlanId = savedState.get<Long>("editorPlanId") ?: Long.MIN_VALUE
    private var loadJob: Job? = null
    private var observeJob: Job? = null
    private var splitJob: Job? = null

    private fun update(transform: (PlanEditUiState) -> PlanEditUiState) {
        _uiState.update(transform)
        savedState["editorDraft"] = _uiState.value.toBundle()
    }

    private fun edit(transform: (PlanEditUiState) -> PlanEditUiState) {
        if (_uiState.value.busy) return
        update { transform(it).copy(isDirty = true, message = null) }
    }

    fun loadPlan(planId: Long?) {
        val key = planId ?: 0L
        if (loadedPlanId == key && !_uiState.value.isLoading) {
            if (key > 0 && observeJob == null) observeSteps(key)
            return
        }
        loadedPlanId = key
        savedState["editorPlanId"] = key
        loadJob?.cancel()
        observeJob?.cancel()
        observeJob = null
        cancelSplit()
        if (key == 0L) {
            update { PlanEditUiState(reminders = listOf(ReminderState())) }
            return
        }
        update { PlanEditUiState(id = key, isEditMode = true, isLoading = true) }
        val displayZone = ZoneId.of(_uiState.value.oneTimeZoneId)
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    repo.observePlan(key).first()?.let { it to stepRepo.getSteps(key) }
                }
                if (loadedPlanId != key) return@launch
                if (loaded == null) {
                    update { it.copy(isLoading = false, message = "这个计划已被删除，请返回后重新选择。") }
                    return@launch
                }
                val (saved, steps) = loaded
                val plan = saved.plan
                val once = plan.oneTimeAt?.takeIf { plan.repeatMode == "ONCE" }
                    ?.let { java.time.Instant.ofEpochMilli(it).atZone(displayZone) }
                update {
                    PlanEditUiState(
                        id = plan.id, name = plan.name, description = plan.description.orEmpty(),
                        category = plan.category, isCompleted = plan.isCompleted, isEnabled = plan.isEnabled,
                        repeatMode = plan.repeatMode, oneTimeZoneId = displayZone.id,
                        oneTimeDate = once?.toLocalDate()?.toString() ?: LocalDate.now(displayZone).toString(),
                        originalOneTimeAt = once?.toInstant()?.toEpochMilli(),
                        customDays = plan.customDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) },
                        reminders = saved.reminders.map { ReminderState(it.id, once?.hour ?: it.hour, once?.minute ?: it.minute, it.isEnabled) },
                        suggestedFocusMinutes = plan.suggestedFocusMinutes,
                        steps = steps.map { it.toEditState() }, isEditMode = true,
                    )
                }
                observeSteps(key)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (loadedPlanId == key) update { it.copy(isLoading = false, message = "读取计划失败，请返回后重试。") } }
        }
    }

    private fun observeSteps(planId: Long) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(repo.observePlan(planId), stepRepo.observe(planId)) { plan, steps -> plan to steps }
                .collect { (plan, steps) ->
                    if (loadedPlanId == planId && !_uiState.value.isDirty && !_uiState.value.isSaving && plan != null) {
                        update { it.copy(steps = steps.map { step -> step.toEditState() },
                            isCompleted = plan.plan.isCompleted, isEnabled = plan.plan.isEnabled) }
                    }
                }
        }
    }

    fun applyVoiceDraft(draft: com.example.myplan.voice.PlanVoiceData) {
        if (_uiState.value.isEditMode || _uiState.value.isDirty) return
        val reminders = draft.reminderTimes.mapNotNull { text ->
            val parts = text.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59)
                ReminderState(hour = parts[0], minute = parts[1]) else null
        }.distinctBy { it.hour to it.minute }
        val once = draft.oneTimeAt?.takeIf { draft.repeatMode == "ONCE" }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.of(_uiState.value.oneTimeZoneId)) }
        update { it.copy(name = draft.name, category = draft.category,
            repeatMode = draft.repeatMode, customDays = draft.customDays,
            creationToken = draft.creationToken ?: it.creationToken,
            oneTimeDate = once?.toLocalDate()?.toString() ?: it.oneTimeDate,
            originalOneTimeAt = once?.toInstant()?.toEpochMilli(),
            suggestedFocusMinutes = draft.focusMinutes?.takeIf { minutes -> minutes in setOf(15, 25, 45) } ?: it.suggestedFocusMinutes,
            reminders = if (draft.repeatMode == "ONCE" && once != null) listOf(ReminderState(hour = once.hour, minute = once.minute))
                else reminders.ifEmpty { listOf(ReminderState()) }, isDirty = true) }
    }

    fun updateName(name: String) { cancelSplit(); edit { it.copy(name = name, suggestions = emptyList(), assistantError = null) } }
    fun updateDescription(desc: String) { cancelSplit(); edit { it.copy(description = desc, suggestions = emptyList(), assistantError = null) } }
    fun updateCategory(cat: String) { edit { it.copy(category = cat) } }
    fun updateOneTimeDate(date: String) { edit { it.copy(oneTimeDate = date) } }
    fun toggleComplete() { edit { it.copy(isCompleted = !it.isCompleted) } }
    fun updateFocusMinutes(minutes: Int) { if (minutes in setOf(15, 25, 45)) edit { it.copy(suggestedFocusMinutes = minutes) } }

    fun updateRepeatMode(mode: String) {
        edit { state ->
            val days = when (mode) {
                "WEEKDAYS" -> setOf(1, 2, 3, 4, 5)
                "CUSTOM" -> state.customDays.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) }
                else -> setOf(1, 2, 3, 4, 5, 6, 7)
            }
            state.copy(repeatMode = mode, customDays = days,
                reminders = if (mode == "ONCE") state.reminders.take(1).ifEmpty { listOf(ReminderState()) } else state.reminders)
        }
    }

    fun toggleCustomDay(day: Int) { edit { state -> state.copy(customDays = state.customDays.toMutableSet().apply { if (!add(day)) remove(day) }) } }
    fun addReminder(hour: Int = 9, minute: Int = 0) { edit { it.copy(reminders = it.reminders + ReminderState(hour = hour, minute = minute)) } }
    fun updateReminder(index: Int, hour: Int, minute: Int) { edit { it.copy(reminders = it.reminders.mapIndexed { i, r -> if (i == index) r.copy(hour = hour, minute = minute) else r }) } }
    fun toggleReminder(index: Int) { edit { it.copy(reminders = it.reminders.mapIndexed { i, r -> if (i == index) r.copy(isEnabled = !r.isEnabled) else r }) } }
    fun removeReminder(index: Int) { edit { it.copy(reminders = it.reminders.filterIndexed { i, _ -> i != index }) } }

    fun addStep() {
        if (_uiState.value.steps.size >= MAX_STEPS) { update { it.copy(message = "最多添加 $MAX_STEPS 个步骤，先从小目标开始。") }; return }
        edit { it.copy(steps = it.steps + PlanStepEditState(focusMinutes = it.suggestedFocusMinutes)) }
    }
    fun updateStepTitle(key: String, title: String) { edit { it.copy(steps = it.steps.map { s -> if (s.key == key) s.copy(title = title) else s }) } }
    fun updateStepMinutes(key: String, minutes: Int) {
        if (minutes in setOf(15, 25, 45)) edit { it.copy(steps = it.steps.map { s -> if (s.key == key) s.copy(focusMinutes = minutes) else s }) }
    }
    fun removeStep(key: String) { edit { it.copy(steps = it.steps.filterNot { s -> s.key == key }) } }

    fun requestSplit() {
        val state = _uiState.value
        if (state.busy || state.isSplitting) return
        if (state.name.isBlank()) { update { it.copy(assistantError = "先写下计划名称，再让 AI 帮你拆解。") }; return }
        splitJob?.cancel()
        update { it.copy(isSplitting = true, assistantError = null, suggestions = emptyList()) }
        val key = loadedPlanId
        splitJob = viewModelScope.launch {
            try {
                val suggestions = assistant.split(state.name, state.description)
                if (loadedPlanId == key && _uiState.value.name == state.name && _uiState.value.description == state.description) {
                    update { it.copy(suggestions = suggestions.map { s -> StepSuggestionState(s.title, s.focusMinutes) }, isSplitting = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AssistException) { update { it.copy(isSplitting = false, assistantError = e.userMessage) } }
            catch (_: Exception) { update { it.copy(isSplitting = false, assistantError = "暂时无法拆解，可以重试，或手动添加步骤。") } }
        }
    }
    fun cancelSplit() { splitJob?.cancel(); splitJob = null; if (_uiState.value.isSplitting) update { it.copy(isSplitting = false) } }
    fun dismissSuggestions() { update { it.copy(suggestions = emptyList()) } }
    fun toggleSuggestion(index: Int) { update { it.copy(suggestions = it.suggestions.mapIndexed { i, s -> if (i == index) s.copy(selected = !s.selected) else s }) } }
    fun updateSuggestion(index: Int, title: String) { update { it.copy(suggestions = it.suggestions.mapIndexed { i, s -> if (i == index) s.copy(title = title) else s }) } }
    fun updateSuggestionMinutes(index: Int, minutes: Int) { if (minutes in setOf(15, 25, 45)) update { it.copy(suggestions = it.suggestions.mapIndexed { i, s -> if (i == index) s.copy(focusMinutes = minutes) else s }) } }
    fun acceptSuggestions(replace: Boolean = false) {
        val state = _uiState.value
        if (state.busy) return
        val selected = state.suggestions.filter { it.selected }
        if (selected.isEmpty() || selected.any { it.title.isBlank() }) { update { it.copy(message = "请勾选步骤，并补全所选步骤的名称。") }; return }
        val additions = selected.map { PlanStepEditState(title = it.title.trim(), focusMinutes = it.focusMinutes) }
        val steps = if (replace) additions else state.steps + additions
        if (steps.size > MAX_STEPS) { update { it.copy(message = "最多保存 $MAX_STEPS 个步骤，请减少勾选或先删除部分步骤。") }; return }
        edit { it.copy(steps = steps, suggestions = emptyList(), assistantError = null) }
    }

    fun toggleStepCompleted(key: String) {
        val state = _uiState.value
        val step = state.steps.find { it.key == key } ?: return
        if (state.busy) return
        if (state.id <= 0 || state.isDirty || step.id <= 0) { update { it.copy(message = "先保存计划，再勾选步骤。") }; return }
        if (state.isCompleted) { update { it.copy(message = "计划已经完成，撤销完成后可继续修改步骤进度。") }; return }
        update { it.copy(updatingStepKey = key, message = null) }
        viewModelScope.launch {
            try {
                val date = LocalDate.now().toString()
                val changed = withContext(Dispatchers.IO) { stepRepo.setCompleted(state.id, step.id, !step.isDone(state.repeatMode, date), date) }
                if (!changed) update { it.copy(message = "计划状态已变化，请重新打开后操作。") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update { it.copy(message = "步骤进度未能保存，请重试。") } }
            finally { update { it.copy(updatingStepKey = null) } }
        }
    }

    fun startStepFocus(key: String, onReady: (Long, Long) -> Unit) {
        val state = _uiState.value
        val step = state.steps.find { it.key == key } ?: return
        if (state.busy) return
        if (state.isDirty || state.id <= 0 || step.id <= 0) { update { it.copy(message = "先保存修改，再开始专注。") }; return }
        update { it.copy(updatingStepKey = key, message = null) }
        viewModelScope.launch {
            try {
                val current = withContext(Dispatchers.IO) { repo.getPlanById(state.id) to stepRepo.getSteps(state.id).find { it.id == step.id } }
                when {
                    current.first == null || current.second == null -> update { it.copy(message = "这个计划或步骤已被删除，请返回后重新选择。") }
                    current.first!!.isCompleted -> update { it.copy(message = "计划已完成，撤销完成后可开始专注。") }
                    !current.first!!.isEnabled -> update { it.copy(message = "计划已暂停，请先恢复计划。") }
                    current.second!!.isDoneOn(current.first!!.repeatMode, LocalDate.now().toString()) -> update { it.copy(message = "这个步骤已经完成，可以选择下一步。") }
                    else -> { cancelSplit(); onReady(state.id, step.id) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update { it.copy(message = "暂时无法开始专注，请重试。") } }
            finally { update { it.copy(updatingStepKey = null) } }
        }
    }

    fun save(onComplete: () -> Unit) {
        if (_uiState.value.busy) return
        val snapshot = _uiState.value
        if (snapshot.steps.any { it.title.isBlank() }) { update { it.copy(message = "给每个步骤起个名字，或删除空步骤后再保存。") }; return }
        cancelSplit()
        update { it.copy(isSaving = true, message = null, suggestions = emptyList()) }
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val state = snapshot
                    val oneTime = if (state.repeatMode == "ONCE") {
                        val rem = state.reminders.singleOrNull() ?: error("请设置一个提醒时间")
                        val displayZone = ZoneId.of(state.oneTimeZoneId)
                        val originalAt = state.originalOneTimeAt ?: repo.getPlanById(state.id)?.oneTimeAt
                        val original = originalAt?.let { java.time.Instant.ofEpochMilli(it).atZone(displayZone) }
                        if (original?.toLocalDate()?.toString() == state.oneTimeDate && original.hour == rem.hour && original.minute == rem.minute) originalAt
                        else com.example.myplan.quickadd.QuickAddParser.resolve(LocalDate.parse(state.oneTimeDate), java.time.LocalTime.of(rem.hour, rem.minute), displayZone)
                            ?.toInstant()?.toEpochMilli() ?: error("这个时间受夏令时影响，请重新选择")
                    } else null
                    if (oneTime != null && !state.isCompleted && oneTime <= System.currentTimeMillis()) {
                        require(state.isEditMode && repo.getPlanById(state.id)?.oneTimeAt == oneTime) { "请选择未来的提醒时间" }
                    }
                    val plan = PlanEntity(id = state.id, name = state.name, description = state.description.ifBlank { null },
                        category = state.category, isEnabled = state.isEnabled && state.reminders.any { it.isEnabled },
                        isCompleted = state.isCompleted, repeatMode = state.repeatMode, oneTimeAt = oneTime,
                        customDays = state.customDays.sorted().joinToString(","), suggestedFocusMinutes = state.suggestedFocusMinutes,
                        creationToken = state.creationToken,
                        updatedAt = System.currentTimeMillis())
                    val reminders = state.reminders.map { ReminderEntity(id = it.id, planId = state.id, hour = it.hour, minute = it.minute, isEnabled = it.isEnabled) }
                    PlanActions(repo, scheduler).save(plan, reminders, state.steps.map { PlanStepDraft(it.id, it.title.trim(), it.focusMinutes) })
                }
                val (savedPlan, savedSteps) = withContext(Dispatchers.IO) { repo.getPlanById(id) to stepRepo.getSteps(id) }
                loadedPlanId = id
                savedState["editorPlanId"] = id
                update { it.copy(id = id, isEditMode = true, isDirty = false, originalOneTimeAt = savedPlan?.oneTimeAt,
                    steps = savedSteps.map { step -> step.toEditState() }) }
                observeSteps(id)
                onComplete()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("PlanEdit", "Save failed", e)
                update { it.copy(message = e.message ?: "保存失败，请重试") }
                if (e is com.example.myplan.notification.AlarmSyncException) {
                    loadedPlanId = e.planId
                    savedState["editorPlanId"] = e.planId
                    val (savedPlan, savedSteps) = withContext(Dispatchers.IO) { repo.getPlanById(e.planId) to stepRepo.getSteps(e.planId) }
                    update { it.copy(id = e.planId, isEditMode = true, isDirty = false, originalOneTimeAt = savedPlan?.oneTimeAt,
                        steps = savedSteps.map { step -> step.toEditState() }) }
                    android.widget.Toast.makeText(getApplication(), e.message, android.widget.Toast.LENGTH_LONG).show()
                    observeSteps(e.planId)
                    onComplete()
                }
            } finally { update { it.copy(isSaving = false) } }
        }
    }

    companion object { const val MAX_STEPS = 8 }
}

private fun PlanStepEntity.toEditState() = PlanStepEditState(id, title, focusMinutes, isCompleted, completedDate, "saved:$id")

private fun PlanEditUiState.toBundle() = Bundle().apply {
    putLong("id", id); putString("name", name); putString("description", description); putString("category", category)
    putBoolean("completed", isCompleted); putBoolean("enabled", isEnabled); putString("repeat", repeatMode); putString("date", oneTimeDate)
    putString("onceZone", oneTimeZoneId); originalOneTimeAt?.let { putLong("originalOnce", it) }
    putIntArray("days", customDays.toIntArray()); putInt("minutes", suggestedFocusMinutes); putBoolean("edit", isEditMode); putBoolean("dirty", isDirty)
    putString("token", creationToken)
    putBoolean("loading", isLoading)
    putParcelableArrayList("reminders", ArrayList(reminders.map { r -> Bundle().apply { putLong("id", r.id); putInt("hour", r.hour); putInt("minute", r.minute); putBoolean("enabled", r.isEnabled) } }))
    putParcelableArrayList("steps", ArrayList(steps.map { s -> Bundle().apply { putLong("id", s.id); putString("title", s.title); putInt("minutes", s.focusMinutes); putBoolean("done", s.isCompleted); putString("date", s.completedDate); putString("key", s.key) } }))
    putParcelableArrayList("suggestions", ArrayList(suggestions.map { s -> Bundle().apply { putString("title", s.title); putInt("minutes", s.focusMinutes); putBoolean("selected", s.selected) } }))
}

@Suppress("DEPRECATION")
private fun restoreDraft(bundle: Bundle?): PlanEditUiState? = bundle?.let { b ->
    PlanEditUiState(id = b.getLong("id"), name = b.getString("name").orEmpty(), description = b.getString("description").orEmpty(),
        category = b.getString("category") ?: "日常", isCompleted = b.getBoolean("completed"), isEnabled = b.getBoolean("enabled", true),
        repeatMode = b.getString("repeat") ?: "EVERY_DAY", oneTimeDate = b.getString("date") ?: LocalDate.now().toString(),
        oneTimeZoneId = b.getString("onceZone") ?: ZoneId.systemDefault().id,
        originalOneTimeAt = if (b.containsKey("originalOnce")) b.getLong("originalOnce") else null,
        customDays = b.getIntArray("days")?.toSet() ?: setOf(1, 2, 3, 4, 5), suggestedFocusMinutes = b.getInt("minutes", 25),
        creationToken = b.getString("token") ?: UUID.randomUUID().toString(), isLoading = b.getBoolean("loading"),
        isEditMode = b.getBoolean("edit"), isDirty = b.getBoolean("dirty"),
        reminders = b.getParcelableArrayList<Bundle>("reminders").orEmpty().map { ReminderState(it.getLong("id"), it.getInt("hour"), it.getInt("minute"), it.getBoolean("enabled", true)) },
        steps = b.getParcelableArrayList<Bundle>("steps").orEmpty().map { PlanStepEditState(it.getLong("id"), it.getString("title").orEmpty(), it.getInt("minutes", 25), it.getBoolean("done"), it.getString("date"), it.getString("key") ?: UUID.randomUUID().toString()) },
        suggestions = b.getParcelableArrayList<Bundle>("suggestions").orEmpty().map { StepSuggestionState(it.getString("title").orEmpty(), it.getInt("minutes", 25), it.getBoolean("selected", true)) })
}
