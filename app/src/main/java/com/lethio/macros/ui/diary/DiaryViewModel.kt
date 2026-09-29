package com.lethio.macros.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DiaryUiState(
    val date: LocalDate,
    val day: DayLog,
    val goals: Goals,
    val isToday: Boolean,
    val loggedDates: List<LocalDate> = emptyList(),
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val goalsRepository: GoalsRepository,
    private val timeProvider: TimeProvider,
    private val currentDiaryDate: CurrentDiaryDate,
) : ViewModel() {

    private val date = MutableStateFlow(timeProvider.today())

    private var followsToday = true

    init {
        currentDiaryDate.set(date.value)
    }

    val uiState: StateFlow<DiaryUiState> = date.flatMapLatest { shown ->
        combine(
            logRepository.observeDay(shown),

            goalsRepository.observeGoalsFor(shown),
            logRepository.observeLoggedDates(),
        ) { day, goals, loggedDates ->
            DiaryUiState(
                date = shown,
                day = day,
                goals = goals,
                isToday = shown == timeProvider.today(),
                loggedDates = loggedDates,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DiaryUiState.initial(date.value, isToday = true),
    )

    private val _lastDeleted = MutableStateFlow<LogEntry?>(null)
    val lastDeleted: StateFlow<LogEntry?> = _lastDeleted

    fun deleteLog(entry: LogEntry) {
        viewModelScope.launch {
            logRepository.deleteEntry(entry.id)
            _lastDeleted.value = entry
        }
    }

    fun undoDelete() {
        val entry = _lastDeleted.value ?: return
        viewModelScope.launch {
            logRepository.restoreEntry(entry)
            _lastDeleted.value = null
        }
    }

    fun clearUndo() {
        _lastDeleted.value = null
    }

    fun refreshDate() {
        if (!followsToday) return
        val today = timeProvider.today()
        if (date.value != today) moveTo(today)
    }

    fun goToPreviousDay() = moveTo(date.value.minusDays(1))

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
        date.value = target
        currentDiaryDate.set(target)
    }
}
