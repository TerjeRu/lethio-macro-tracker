package com.lethio.macros.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.repository.GoalsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SavedGoalPeriod(val goals: Goals, val nextDate: LocalDate?)
data class SavedGoalEdit(val period: SavedGoalPeriod, val fields: GoalsUiState)
data class SavedGoalsUiState(
    val periods: List<SavedGoalPeriod> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val editing: SavedGoalEdit? = null,
    val deleting: SavedGoalPeriod? = null,
    val deletingBusy: Boolean = false,
    val deleteFailed: Boolean = false,
)

@HiltViewModel
class SavedGoalsViewModel @Inject constructor(
    private val repository: GoalsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val requestedDate = savedState.get<String>("goalsDate")?.takeUnless { it == "all" }?.let(LocalDate::parse)
    private val _state = MutableStateFlow(SavedGoalsUiState())
    val state = _state.asStateFlow()
    private var reader: Job? = null
    private var openedInitial = false

    init { reload() }

    fun reload() {
        reader?.cancel()
        _state.value = _state.value.copy(loading = true, loadFailed = false)
        reader = viewModelScope.launch {
            try {
                repository.observeHistory().collect { goals ->
                    val sorted = goals.sortedByDescending { it.effectiveFrom }
                    val periods = sorted.mapIndexed { i, goal ->
                        SavedGoalPeriod(goal, sorted.getOrNull(i - 1)?.effectiveFrom)
                    }
                    _state.value = _state.value.copy(periods = periods, loading = false, loadFailed = false)
                    if (!openedInitial) {
                        openedInitial = true
                        requestedDate?.let { date ->
                            periods.firstOrNull { it.goals.effectiveFrom <= date }?.let(::edit)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(loading = false, loadFailed = true) }
        }
    }

    fun edit(period: SavedGoalPeriod) {
        if (_state.value.deletingBusy || _state.value.editing?.fields?.saving == true) return
        val m = period.goals.targets
        fun input(v: Double) = if (v.isFinite() && v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        _state.value = _state.value.copy(editing = SavedGoalEdit(period, GoalsUiState(
            calories = input(m.calories), protein = input(m.proteinG), fat = input(m.fatG), carbs = input(m.carbsG),
            inheritedTargets = m,
        )), deleting = null, deleteFailed = false)
    }

    fun change(field: GoalField, value: String) {
        val edit = _state.value.editing?.takeUnless { it.fields.saving } ?: return
        val fields = when (field) {
            GoalField.CALORIES -> edit.fields.copy(calories = value)
            GoalField.PROTEIN -> edit.fields.copy(protein = value)
            GoalField.FAT -> edit.fields.copy(fat = value)
            GoalField.CARBS -> edit.fields.copy(carbs = value)
        }.copy(failed = false)
        _state.value = _state.value.copy(editing = edit.copy(fields = fields))
    }

    fun dismissEdit() { if (_state.value.editing?.fields?.saving != true) _state.value = _state.value.copy(editing = null) }

    fun save() {
        val edit = _state.value.editing?.takeIf { it.fields.canSave } ?: return
        val targets = edit.fields.resolvedTargets() ?: return
        _state.value = _state.value.copy(editing = edit.copy(fields = edit.fields.copy(saving = true)))
        viewModelScope.launch {
            try {
                if (targets != edit.period.goals.targets)
                    repository.replaceSavedGoals(edit.period.goals, edit.period.nextDate, targets)
                _state.value = _state.value.copy(editing = null)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(editing = edit)
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(editing = edit.copy(fields = edit.fields.copy(failed = true)))
            }
        }
    }

    fun requestDelete(period: SavedGoalPeriod) {
        if (_state.value.editing?.fields?.saving == true || _state.value.deletingBusy) return
        _state.value = _state.value.copy(deleting = period, deleteFailed = false)
    }
    fun dismissDelete() { if (!_state.value.deletingBusy) _state.value = _state.value.copy(deleting = null) }
    fun delete() {
        val period = _state.value.deleting?.takeUnless { _state.value.deletingBusy } ?: return
        _state.value = _state.value.copy(deletingBusy = true, deleteFailed = false)
        viewModelScope.launch {
            try {
                repository.deleteSavedGoals(period.goals, period.nextDate)
                _state.value = _state.value.copy(deleting = null, deletingBusy = false)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(deletingBusy = false)
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(deletingBusy = false, deleteFailed = true)
            }
        }
    }
}
