package com.lethio.macros.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.core.dateRefreshDelayMillis
import com.lethio.macros.domain.model.DiaryHistoryDay
import com.lethio.macros.domain.model.DiaryHistoryPeriod
import com.lethio.macros.domain.model.DiaryHistoryWindow
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.repository.DiaryHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

enum class DiaryHistoryMetric {
    ENERGY, PROTEIN, CARBS, FAT;

    fun value(macros: Macros): Double = when (this) {
        ENERGY -> macros.calories
        PROTEIN -> macros.proteinG
        CARBS -> macros.carbsG
        FAT -> macros.fatG
    }
}

data class DiaryHistoryUiState(
    val window: DiaryHistoryWindow,
    val days: List<DiaryHistoryDay> = emptyList(),
    val metric: DiaryHistoryMetric = DiaryHistoryMetric.ENERGY,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Local display choices survive recreation; absent rows and failed reads stay distinct. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryHistoryViewModel @Inject constructor(
    private val repository: DiaryHistoryRepository,
    private val timeProvider: TimeProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val today = MutableStateFlow(timeProvider.today())
    private val selectedDate = savedState.getStateFlow("historyDate", today.value.toString())
    private val period = savedState.getStateFlow("historyPeriod", DiaryHistoryPeriod.SEVEN_DAYS.name)
    private val metric = savedState.getStateFlow("historyMetric", DiaryHistoryMetric.ENERGY.name)
    private val retry = MutableStateFlow(0)

    private val window = combine(selectedDate, period, today) { date, range, currentToday ->
        DiaryHistoryWindow.endingOn(LocalDate.parse(date), currentToday, DiaryHistoryPeriod.valueOf(range))
    }.distinctUntilChanged()

    // stateIn retains the last rows while unsubscribed. Do not briefly replace them with an
    // empty loading list on return to the same window: that clamps the restored scroll position.
    // New windows and failed reads still use the explicit loading/failure states.
    private var loadedWindow: DiaryHistoryWindow? = null

    private val data = combine(window, retry) { range, _ -> range }.flatMapLatest { range ->
        flow {
            if (loadedWindow != range) emit(DiaryHistoryUiState(window = range))
            emitAll(repository.observeWindow(range).map {
                loadedWindow = range
                DiaryHistoryUiState(window = range, days = it, loading = false)
            })
        }
            .catch {
                loadedWindow = null
                emit(DiaryHistoryUiState(window = range, loading = false, failed = true))
            }
    }

    // Saved targets are always drawn.
    val uiState = combine(data, metric) { state, selectedMetric ->
        state.copy(metric = DiaryHistoryMetric.valueOf(selectedMetric))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DiaryHistoryUiState(
            window = DiaryHistoryWindow.endingOn(
                LocalDate.parse(selectedDate.value), today.value, DiaryHistoryPeriod.valueOf(period.value),
            ),
            metric = DiaryHistoryMetric.valueOf(metric.value),
        ),
    )

    fun selectPeriod(value: DiaryHistoryPeriod) { savedState["historyPeriod"] = value.name }
    fun selectMetric(value: DiaryHistoryMetric) { savedState["historyMetric"] = value.name }
    fun retry() { retry.value += 1 }
    fun refreshDate() { today.value = timeProvider.today() }
    fun dateRefreshDelayMillis(): Long = timeProvider.dateRefreshDelayMillis()
}
