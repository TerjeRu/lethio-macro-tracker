package com.lethio.macros.ui.diary

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.DiaryHistoryDay
import com.lethio.macros.domain.model.DiaryHistoryPeriod
import com.lethio.macros.domain.model.DiaryHistoryWindow
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.repository.DiaryHistoryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val date = LocalDate.of(2026, 10, 4)
    private val time =
        object : TimeProvider {
            var date = this@DiaryHistoryViewModelTest.date

            override fun today() = date

            override fun now() = Instant.EPOCH

            override fun currentTime() = LocalTime.NOON
        }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `missing and logged zero survive observation and live updates`() =
        runTest(dispatcher) {
            val rows =
                MutableStateFlow(
                    listOf(
                        DiaryHistoryDay(date.minusDays(1), 0, null, null),
                        DiaryHistoryDay(date, 1, Macros.ZERO, null),
                    )
                )
            val repository =
                object : DiaryHistoryRepository {
                    override fun observeWindow(window: DiaryHistoryWindow) = rows
                }
            val vm = DiaryHistoryViewModel(repository, time, SavedStateHandle())
            vm.uiState.test {
                assertThat(awaitItem().loading).isTrue()
                val loaded = awaitItem()
                assertThat(loaded.days[0].loggedIntake).isNull()
                assertThat(loaded.days[1].loggedIntake).isEqualTo(Macros.ZERO)
                rows.value = rows.value.dropLast(1) + DiaryHistoryDay(date, 0, null, null)
                assertThat(awaitItem().days[1].loggedIntake).isNull()
            }
        }

    @Test
    fun `failure is separate from empty data and retry observes again`() =
        runTest(dispatcher) {
            var calls = 0
            val repository =
                object : DiaryHistoryRepository {
                    override fun observeWindow(
                        window: DiaryHistoryWindow
                    ): Flow<List<DiaryHistoryDay>> = flow {
                        calls++
                        if (calls == 1) error("Synthetic read failure")
                        emit(emptyList())
                    }
                }
            val vm = DiaryHistoryViewModel(repository, time, SavedStateHandle())
            vm.uiState.test {
                awaitItem()
                val failed = awaitItem()
                assertThat(failed.failed).isTrue()
                assertThat(failed.loading).isFalse()
                vm.retry()
                var recovered = awaitItem()
                while (recovered.loading) recovered = awaitItem()
                assertThat(recovered.failed).isFalse()
                assertThat(recovered.days).isEmpty()
                assertThat(calls).isEqualTo(2)
            }
        }

    @Test
    fun `saved choices restore and old selected date does not follow midnight`() =
        runTest(dispatcher) {
            val windows = mutableListOf<DiaryHistoryWindow>()
            val repository =
                object : DiaryHistoryRepository {
                    override fun observeWindow(window: DiaryHistoryWindow) = flow {
                        windows += window
                        emit(emptyList<DiaryHistoryDay>())
                    }
                }
            val handle = SavedStateHandle(mapOf("historyDate" to date.minusDays(2).toString()))
            val vm = DiaryHistoryViewModel(repository, time, handle)
            vm.uiState.test {
                awaitItem()
                awaitItem()
                vm.selectPeriod(DiaryHistoryPeriod.TWENTY_EIGHT_DAYS)
                var changed = awaitItem()
                while (changed.loading) changed = awaitItem()
                assertThat(changed.window.end).isEqualTo(date.minusDays(2))
                assertThat(changed.window.period.days).isEqualTo(28)
                vm.selectMetric(DiaryHistoryMetric.CARBS)
                assertThat(awaitItem().metric).isEqualTo(DiaryHistoryMetric.CARBS)
                time.date = date.plusDays(1)
                vm.refreshDate()
                expectNoEvents()
                assertThat(windows).hasSize(2)
            }
            val restored =
                DiaryHistoryViewModel(
                    repository,
                    time,
                    SavedStateHandle(
                        mapOf(
                            "historyDate" to handle.get<String>("historyDate"),
                            "historyPeriod" to handle.get<String>("historyPeriod"),
                            "historyMetric" to handle.get<String>("historyMetric"),
                        )
                    ),
                )
            restored.uiState.test {
                awaitItem()
                var loaded = awaitItem()
                while (loaded.loading) loaded = awaitItem()
                assertThat(loaded.metric).isEqualTo(DiaryHistoryMetric.CARBS)
                assertThat(loaded.window.period).isEqualTo(DiaryHistoryPeriod.TWENTY_EIGHT_DAYS)
            }
        }

    @Test
    fun `return after observer timeout retains rows until fresh snapshots arrive`() =
        runTest(dispatcher) {
            val rows = MutableStateFlow(listOf(DiaryHistoryDay(date, 1, Macros(200.0), null)))
            var calls = 0
            val repository =
                object : DiaryHistoryRepository {
                    override fun observeWindow(window: DiaryHistoryWindow) = flow {
                        calls++
                        delay(100)
                        emitAll(rows)
                    }
                }
            val vm = DiaryHistoryViewModel(repository, time, SavedStateHandle())
            vm.uiState.test {
                awaitItem()
                advanceTimeBy(101)
                runCurrent()
                assertThat(awaitItem().days.single().loggedIntake?.calories).isEqualTo(200.0)
            }
            advanceTimeBy(5_001)
            runCurrent()
            rows.value = listOf(DiaryHistoryDay(date, 1, Macros(250.0), null))
            vm.uiState.test {
                assertThat(awaitItem().days.single().loggedIntake?.calories).isEqualTo(200.0)
                runCurrent()
                expectNoEvents() // No empty loading list to clamp the saved scroll position.
                advanceTimeBy(101)
                runCurrent()
                assertThat(awaitItem().days.single().loggedIntake?.calories).isEqualTo(250.0)
                assertThat(calls).isEqualTo(2)
            }
        }
}
