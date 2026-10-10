package com.lethio.macros.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.core.dateRefreshDelayMillis
import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import javax.inject.Inject

data class DiaryDeletion(val entries: List<LogEntry>, val wholeDay: Boolean)

data class DiaryUiState(
    val date: LocalDate,
    val day: DayLog,
    val goals: Goals,
    val isToday: Boolean,
    val loggedDates: List<LocalDate> = emptyList(),
    /** The day before [date], for copying a meal from yesterday. */
    val previousDay: DayLog = DayLog.empty(date.minusDays(1)),
) {
    companion object {
        fun initial(date: LocalDate, isToday: Boolean) = DiaryUiState(
            date = date,
            day = DayLog.empty(date),
            goals = Goals.default(date),
            isToday = isToday,
        )
    }
}

/**
 * The diary for one date, and the source of [CurrentDiaryDate], so a food found through Search or
 * Scan logs to the day shown here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val goalsRepository: GoalsRepository,
    private val timeProvider: TimeProvider,
    private val currentDiaryDate: CurrentDiaryDate,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val date = MutableStateFlow(if (savedState.get<Boolean>("followsToday") != false) {
        timeProvider.today()
    } else {
        savedState.get<String>("selectedDiaryDate")?.let(LocalDate::parse)
            ?.coerceAtMost(timeProvider.today()) ?: timeProvider.today()
    })
    private val _today = MutableStateFlow(timeProvider.today())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /**
     * Whether [date] follows "today" across midnight. True until the reader picks another day;
     * [refreshDate] only moves the date forward while it holds, so the app never logs into
     * yesterday and never yanks a reader away from the day they chose.
     */
    private var followsToday = savedState.get<Boolean>("followsToday") ?: true

    init {
        currentDiaryDate.set(date.value)
    }

    val uiState: StateFlow<DiaryUiState> = date.flatMapLatest { shown ->
        combine(
            logRepository.observeDay(shown),
            // The goals in force on the shown date, not today's.
            goalsRepository.observeGoalsFor(shown),
            logRepository.observeLoggedDates(),
            today,
            logRepository.observeDay(shown.minusDays(1)),
        ) { day, goals, loggedDates, currentToday, previous ->
            DiaryUiState(
                date = shown,
                day = day,
                goals = goals,
                isToday = shown == currentToday,
                loggedDates = loggedDates,
                previousDay = previous,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DiaryUiState.initial(date.value, isToday = date.value == today.value),
    )

    private val _lastDeleted = MutableStateFlow<DiaryDeletion?>(null)
    val lastDeleted: StateFlow<DiaryDeletion?> = _lastDeleted
    private val _actionBusy = MutableStateFlow(false)
    val actionBusy = _actionBusy.asStateFlow()
    private val _actionFailed = MutableStateFlow(false)
    val actionFailed = _actionFailed.asStateFlow()
    private val _clearDate = MutableStateFlow<LocalDate?>(null)
    val clearDate = _clearDate.asStateFlow()

    fun deleteLog(entry: LogEntry) {
        if (_actionBusy.value) return
        changeEntries {
            logRepository.deleteEntry(entry.id)
            _lastDeleted.value = DiaryDeletion(listOf(entry), wholeDay = false)
        }
    }

    fun requestClearDay() { if (!_actionBusy.value) _clearDate.value = date.value }
    fun dismissClearDay() { if (!_actionBusy.value) _clearDate.value = null }
    fun clearDay() {
        val target = _clearDate.value ?: return
        if (_actionBusy.value) return
        changeEntries {
            val removed = logRepository.clearDay(target)
            _clearDate.value = null
            if (removed.isNotEmpty()) _lastDeleted.value = DiaryDeletion(removed, wholeDay = true)
        }
    }

    fun undoDelete() {
        val deletion = _lastDeleted.value ?: return
        if (_actionBusy.value) return
        changeEntries {
            if (deletion.wholeDay) logRepository.restoreEntries(deletion.entries)
            else logRepository.restoreEntry(deletion.entries.single())
            _lastDeleted.value = null
        }
    }

    private fun changeEntries(action: suspend () -> Unit) {
        _actionBusy.value = true
        _actionFailed.value = false
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _actionFailed.value = true }
            finally { _actionBusy.value = false }
        }
    }

    private val _lastCopied = MutableStateFlow<List<Long>?>(null)
    /** Ids of the entries the last "copy meal" created, until its Undo is used or dismissed. */
    val lastCopied: StateFlow<List<Long>?> = _lastCopied

    /**
     * Copies [meal] from the day before into the shown day, with the same amounts and saved
     * nutrition snapshots, credits included. Nothing is re-read from the catalogue.
     */
    fun copyMealFromPreviousDay(meal: com.lethio.macros.domain.model.MealType) {
        if (_actionBusy.value) return
        val state = uiState.value
        val source = state.previousDay.entriesByMeal[meal].orEmpty()
        if (source.isEmpty()) return
        changeEntries {
            val now = timeProvider.now()
            _lastCopied.value = source.map { entry ->
                logRepository.addEntry(entry.copy(id = LogEntry.NEW, date = state.date, createdAt = now, updatedAt = now))
            }
        }
    }

    fun undoCopy() {
        val ids = _lastCopied.value ?: return
        if (_actionBusy.value) return
        changeEntries {
            ids.forEach { logRepository.deleteEntry(it) }
            _lastCopied.value = null
        }
    }

    fun clearCopyUndo() { if (!_actionBusy.value) _lastCopied.value = null }

    fun clearFailure() { _actionFailed.value = false }
    fun clearUndo() { if (!_actionBusy.value) _lastDeleted.value = null }

    private val _editing = MutableStateFlow<EntryEditState?>(null)
    val editing: StateFlow<EntryEditState?> = _editing

    fun editEntry(entry: LogEntry) { _editing.value = EntryEditState(entry) }
    fun editAmount(amount: String) {
        _editing.value?.takeUnless { it.saving }?.let { _editing.value = it.copy(amount = amount, failed = false) }
    }
    fun editMeal(meal: com.lethio.macros.domain.model.MealType) {
        _editing.value?.takeUnless { it.saving }?.let { _editing.value = it.copy(meal = meal, failed = false) }
    }
    fun cancelEdit() { if (_editing.value?.saving != true) _editing.value = null }
    fun saveEdit() {
        val draft = _editing.value?.takeUnless { it.saving } ?: return
        val corrected = correctedEntry(draft.original, draft.amount, draft.meal) ?: return
        _editing.value = draft.copy(saving = true, failed = false)
        viewModelScope.launch {
            try {
                if (corrected != draft.original) logRepository.updateEntry(corrected)
                _editing.value = null
            } catch (cancelled: CancellationException) {
                _editing.value = draft
                throw cancelled
            } catch (_: Exception) {
                _editing.value = draft.copy(failed = true)
            }
        }
    }

    fun refreshDate() {
        val today = timeProvider.today()
        if (followsToday && date.value != today) moveTo(today)
        _today.value = today
    }

    fun dateRefreshDelayMillis(): Long = timeProvider.dateRefreshDelayMillis()

    fun goToPreviousDay() = moveTo(date.value.minusDays(1))

    /** Stops at today. */
    fun goToNextDay() {
        val next = date.value.plusDays(1)
        if (next > timeProvider.today()) return
        moveTo(next)
    }

    fun goToDate(target: LocalDate) {
        val today = timeProvider.today()
        moveTo(if (target > today) today else target)
    }

    fun goToToday() = moveTo(timeProvider.today())

    private fun moveTo(target: LocalDate) {
        followsToday = target == timeProvider.today()
        savedState["selectedDiaryDate"] = target.toString()
        savedState["followsToday"] = followsToday
        date.value = target
        currentDiaryDate.set(target)
    }
}
