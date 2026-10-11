package com.lethio.macros.ui.diary

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.LogRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Which day the diary shows: it must not yank the reader back to today mid-browse, and must not
 * wander into a future date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `goToNextDay does not advance past today`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val viewModel = viewModel(time)

            viewModel.uiState.test {
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 29))
                viewModel.goToNextDay()
                expectNoEvents()
            }
        }

    @Test
    fun `goToDate clamps a future date to today`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val viewModel = viewModel(time)

            viewModel.uiState.test {
                awaitItem()
                // Navigate away first: otherwise the clamped target equals the already-current date
                // and a StateFlow conflates the no-op, proving nothing about the clamp itself.
                viewModel.goToPreviousDay()
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 28))
                viewModel.goToDate(LocalDate.of(2026, 9, 15))
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 29))
            }
        }

    @Test
    fun `refreshDate does not snap back to today once the reader has navigated away`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val currentDiaryDate = CurrentDiaryDate(time)
            val viewModel = viewModel(time, currentDiaryDate)

            viewModel.uiState.test {
                awaitItem()
                viewModel.goToPreviousDay()
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 28))

                // Midnight rolled over while the reader was looking at yesterday.
                time.date = LocalDate.of(2026, 8, 30)
                viewModel.refreshDate()
                expectNoEvents()
            }
            // Search/Scan read this, not the ViewModel's own state -- must reflect the same day.
            assertThat(currentDiaryDate.value).isEqualTo(LocalDate.of(2026, 8, 28))
        }

    @Test
    fun `refreshDate advances the shown day while still following today`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val viewModel = viewModel(time)

            viewModel.uiState.test {
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 29))
                time.date = LocalDate.of(2026, 8, 30)
                viewModel.refreshDate()
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 30))
            }
        }

    @Test
    fun `goToToday returns to today after browsing the past`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val viewModel = viewModel(time)

            viewModel.uiState.test {
                awaitItem()
                viewModel.goToPreviousDay()
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 28))
                viewModel.goToToday()
                assertThat(awaitItem().date).isEqualTo(LocalDate.of(2026, 8, 29))
            }
        }

    @Test
    fun `deleteLog then undoDelete restores the entry`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(LocalDate.of(2026, 8, 29))
            val log = FakeLogRepository()
            val viewModel = viewModel(time, log = log)
            val entry = ENTRY

            viewModel.deleteLog(entry)
            dispatcher.scheduler.runCurrent()
            assertThat(log.deleted).containsExactly(entry.id)

            viewModel.undoDelete()
            dispatcher.scheduler.runCurrent()
            assertThat(log.restored).containsExactly(entry)
        }

    @Test
    fun `midnight refresh updates editor context but preserves original entry and draft`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(ENTRY.date)
            val vm = viewModel(time)
            vm.editEntry(ENTRY)
            vm.editAmount("60")
            time.date = ENTRY.date.plusDays(1)
            vm.refreshDate()
            assertThat(vm.today.value).isEqualTo(ENTRY.date.plusDays(1))
            assertThat(vm.editing.value!!.original).isEqualTo(ENTRY)
            assertThat(vm.editing.value!!.amount).isEqualTo("60")
        }

    @Test
    fun `copying a meal from the day before adds its entries to the shown day and Undo removes them`() =
        runTest(dispatcher) {
            // The copy keeps the saved snapshot (amount, macros), only the day changes.
            val today = ENTRY.date.plusDays(1)
            val log = FakeLogRepository()
            val second = ENTRY.copy(id = ENTRY.id + 1, foodName = "Second")
            log.days[ENTRY.date] =
                DayLog(
                    ENTRY.date,
                    mapOf(ENTRY.meal to listOf(ENTRY, second)),
                    ENTRY.macros + second.macros,
                )
            val vm = viewModel(FakeTimeProvider(today), log = log)
            backgroundScope.launch { vm.uiState.collect {} }
            dispatcher.scheduler.runCurrent()
            assertThat(vm.uiState.value.previousDay.entriesByMeal[ENTRY.meal]).hasSize(2)

            vm.copyMealFromPreviousDay(ENTRY.meal)
            dispatcher.scheduler.runCurrent()
            assertThat(log.added.map { it.date }).containsExactly(today, today)
            assertThat(log.added.map { it.id }).containsExactly(LogEntry.NEW, LogEntry.NEW)
            assertThat(log.added.map { it.macros })
                .containsExactly(ENTRY.macros, second.macros)
                .inOrder()
            assertThat(log.added.map { it.quantity })
                .containsExactly(ENTRY.quantity, second.quantity)
                .inOrder()
            assertThat(vm.lastCopied.value).containsExactly(1001L, 1002L)

            vm.undoCopy()
            dispatcher.scheduler.runCurrent()
            assertThat(log.deleted).containsExactly(1001L, 1002L)
            assertThat(vm.lastCopied.value).isNull()
        }

    @Test
    fun `copying an empty meal writes nothing`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val vm = viewModel(FakeTimeProvider(ENTRY.date.plusDays(1)), log = log)
            backgroundScope.launch { vm.uiState.collect {} }
            dispatcher.scheduler.runCurrent()
            vm.copyMealFromPreviousDay(ENTRY.meal)
            dispatcher.scheduler.runCurrent()
            assertThat(log.added).isEmpty()
            assertThat(vm.lastCopied.value).isNull()
        }

    private fun viewModel(
        time: TimeProvider,
        currentDiaryDate: CurrentDiaryDate = CurrentDiaryDate(time),
        log: LogRepository = FakeLogRepository(),
    ) =
        DiaryViewModel(
            logRepository = log,
            goalsRepository = FakeGoalsRepository(),
            timeProvider = time,
            currentDiaryDate = currentDiaryDate,
        )

    @Test
    fun `failed edit retains draft and retry saves only once`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val vm = viewModel(FakeTimeProvider(ENTRY.date), log = log)
            vm.editEntry(ENTRY)
            vm.editMeal(com.lethio.macros.domain.model.MealType.DINNER)
            log.failUpdate = true
            vm.saveEdit()
            dispatcher.scheduler.runCurrent()
            assertThat(vm.editing.value!!.failed).isTrue()
            assertThat(vm.editing.value!!.meal)
                .isEqualTo(com.lethio.macros.domain.model.MealType.DINNER)
            log.failUpdate = false
            vm.saveEdit()
            vm.saveEdit()
            dispatcher.scheduler.runCurrent()
            assertThat(vm.editing.value).isNull()
            assertThat(log.updated).hasSize(1)
            assertThat(log.updated.single().id).isEqualTo(ENTRY.id)
            assertThat(log.updated.single().macros).isEqualTo(ENTRY.macros)
        }

    @Test
    fun `cancel and unchanged save never write to repository`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val vm = viewModel(FakeTimeProvider(ENTRY.date), log = log)
            vm.editEntry(ENTRY)
            vm.editMeal(com.lethio.macros.domain.model.MealType.DINNER)
            vm.cancelEdit()
            assertThat(vm.editing.value).isNull()
            vm.editEntry(ENTRY)
            vm.saveEdit()
            dispatcher.scheduler.runCurrent()
            assertThat(log.updated).isEmpty()
            assertThat(vm.editing.value).isNull()
        }

    @Test
    fun `restored historical diary date also restores the logging destination`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(ENTRY.date.plusDays(3))
            val current = CurrentDiaryDate(time)
            val saved = androidx.lifecycle.SavedStateHandle()
            val original =
                DiaryViewModel(FakeLogRepository(), FakeGoalsRepository(), time, current, saved)
            original.goToDate(ENTRY.date)
            original.goToPreviousDay()
            val restored =
                DiaryViewModel(
                    FakeLogRepository(),
                    FakeGoalsRepository(),
                    time,
                    current,
                    androidx.lifecycle.SavedStateHandle(
                        mapOf(
                            "selectedDiaryDate" to saved.get<String>("selectedDiaryDate"),
                            "followsToday" to saved.get<Boolean>("followsToday"),
                        )
                    ),
                )
            assertThat(current.value).isEqualTo(ENTRY.date.minusDays(1))
            restored.uiState.test {
                assertThat(awaitItem().date).isEqualTo(ENTRY.date.minusDays(1))
                time.date = time.date.plusDays(1)
                restored.refreshDate()
                expectNoEvents()
            }
        }

    @Test
    fun `restoring a diary that follows today refreshes the destination before any screen resumes`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(ENTRY.date.plusDays(1))
            val current = CurrentDiaryDate(time)
            val vm =
                DiaryViewModel(
                    FakeLogRepository(),
                    FakeGoalsRepository(),
                    time,
                    current,
                    androidx.lifecycle.SavedStateHandle(
                        mapOf("selectedDiaryDate" to ENTRY.date.toString(), "followsToday" to true)
                    ),
                )
            assertThat(current.value).isEqualTo(time.today())
            assertThat(vm.uiState.value.date).isEqualTo(time.today())
        }

    @Test
    fun `clear confirmation captures date even if diary navigation changes`() =
        runTest(dispatcher) {
            val time = FakeTimeProvider(ENTRY.date.plusDays(1))
            val log = FakeLogRepository().apply { clearResult = listOf(ENTRY) }
            val vm = viewModel(time, log = log)
            vm.goToDate(ENTRY.date)
            vm.requestClearDay()
            vm.goToToday()
            vm.clearDay()
            vm.clearDay()
            dispatcher.scheduler.runCurrent()
            assertThat(log.cleared).containsExactly(ENTRY.date)
            assertThat(vm.clearDate.value).isNull()
            assertThat(vm.lastDeleted.value!!.wholeDay).isTrue()
            vm.undoDelete()
            dispatcher.scheduler.runCurrent()
            assertThat(log.batchRestored).containsExactly(ENTRY)
            assertThat(vm.lastDeleted.value).isNull()
        }

    @Test
    fun `failed clear retains confirmation and failed undo retains snapshots for retry`() =
        runTest(dispatcher) {
            val log =
                FakeLogRepository().apply {
                    clearResult = listOf(ENTRY)
                    failClear = true
                }
            val vm = viewModel(FakeTimeProvider(ENTRY.date), log = log)
            vm.requestClearDay()
            vm.clearDay()
            dispatcher.scheduler.runCurrent()
            assertThat(vm.actionFailed.value).isTrue()
            assertThat(vm.clearDate.value).isEqualTo(ENTRY.date)
            assertThat(vm.lastDeleted.value).isNull()
            log.failClear = false
            vm.clearDay()
            dispatcher.scheduler.runCurrent()
            log.failRestore = true
            vm.undoDelete()
            dispatcher.scheduler.runCurrent()
            assertThat(vm.actionFailed.value).isTrue()
            assertThat(vm.lastDeleted.value!!.entries).containsExactly(ENTRY)
            log.failRestore = false
            vm.undoDelete()
            dispatcher.scheduler.runCurrent()
            assertThat(log.batchRestored).containsExactly(ENTRY)
            assertThat(vm.lastDeleted.value).isNull()
        }

    @Test
    fun `cancelled clear does not write`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val vm = viewModel(FakeTimeProvider(ENTRY.date), log = log)
            vm.requestClearDay()
            vm.dismissClearDay()
            vm.clearDay()
            dispatcher.scheduler.runCurrent()
            assertThat(log.cleared).isEmpty()
        }

    private class FakeTimeProvider(var date: LocalDate) : TimeProvider {
        override fun today(): LocalDate = date

        override fun now(): Instant = Instant.parse("2026-08-29T09:00:00Z")

        override fun currentTime(): LocalTime = LocalTime.of(9, 0)
    }

    private class FakeLogRepository : LogRepository {
        val deleted = mutableListOf<Long>()
        val restored = mutableListOf<LogEntry>()
        val updated = mutableListOf<LogEntry>()
        var failUpdate = false
        var failClear = false
        var failRestore = false
        var clearResult = emptyList<LogEntry>()
        val cleared = mutableListOf<LocalDate>()
        val batchRestored = mutableListOf<LogEntry>()
        val days = mutableMapOf<LocalDate, DayLog>()
        val added = mutableListOf<LogEntry>()

        override fun observeDay(date: LocalDate): Flow<DayLog> =
            flowOf(days[date] ?: DayLog.empty(date))

        override fun observeLoggedDates(): Flow<List<LocalDate>> = flowOf(emptyList())

        override suspend fun clearDay(date: LocalDate): List<LogEntry> {
            if (failClear) error("Simulated write failure")
            cleared += date
            return clearResult
        }

        override suspend fun restoreEntries(entries: List<LogEntry>) {
            if (failRestore) error("Simulated write failure")
            batchRestored += entries
        }

        override suspend fun deleteEntry(id: Long) {
            deleted += id
        }

        override suspend fun restoreEntry(entry: LogEntry) {
            restored += entry
        }

        override suspend fun getEntry(id: Long): LogEntry? = error("not used")

        override suspend fun addEntry(entry: LogEntry): Long {
            added += entry
            return 1000L + added.size
        }

        override suspend fun updateEntry(entry: LogEntry) {
            if (failUpdate) error("Simulated write failure")
            updated += entry
        }

        override suspend fun recentFoods(limit: Int): List<LogEntry> = error("not used")

        override suspend fun allEntries(): List<LogEntry> = error("not used")

        override fun observeFavorites(): Flow<List<LogEntry>> = error("not used")

        override fun observeIsFavorite(ref: FoodRef): Flow<Boolean> = error("not used")

        override suspend fun toggleFavorite(entry: LogEntry) = error("not used")
    }

    private class FakeGoalsRepository : GoalsRepository {
        override fun observeGoalsFor(date: LocalDate): Flow<Goals> = flowOf(Goals.default(date))

        override suspend fun goalsFor(date: LocalDate): Goals = Goals.default(date)

        override suspend fun replaceSavedGoals(
            expected: com.lethio.macros.domain.model.Goals,
            nextDate: java.time.LocalDate?,
            targets: com.lethio.macros.domain.model.Macros,
        ): Unit = error("not used")

        override suspend fun deleteSavedGoals(
            expected: com.lethio.macros.domain.model.Goals,
            nextDate: java.time.LocalDate?,
        ): Unit = error("not used")

        override suspend fun setGoals(goals: Goals) = error("not used")

        override fun observeHistory(): Flow<List<Goals>> = error("not used")
    }

    private companion object {
        val ENTRY =
            LogEntry(
                id = 7L,
                date = LocalDate.of(2026, 8, 29),
                meal = com.lethio.macros.domain.model.MealType.LUNCH,
                foodRef = FoodRef.QuickAdd,
                foodName = "Sandwich",
                quantity = com.lethio.macros.domain.model.Quantity.ofGrams(0.0),
                macros = com.lethio.macros.domain.model.Macros.ZERO,
                createdAt = Instant.parse("2026-08-29T09:00:00Z"),
                updatedAt = Instant.parse("2026-08-29T09:00:00Z"),
            )
    }
}
