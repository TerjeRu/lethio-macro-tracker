package com.lethio.macros.ui.log

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.repository.LogRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * If Quick Add enabled its save button on the name alone, a diary entry could hold **−500 kcal**
 * and subtract from the day's totals permanently.
 *
 * Both gates are asserted separately: the button's enabled state, and [QuickAddViewModel.save]
 * itself. They derive from the same value today, and a save that trusts its caller to have checked
 * is one refactor from being the hole again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a negative macro cannot be saved`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val viewModel = viewModel(log)
            viewModel.onNameChanged("Dinner")
            viewModel.onCaloriesChanged("-500")

            assertThat(viewModel.state.value.canSave).isFalse()

            var done = false
            viewModel.save { done = true }
            runCurrent()

            assertThat(done).isFalse()
            assertThat(log.added).isEmpty()
        }

    @Test
    fun `NaN and Infinity cannot be saved either`() =
        runTest(dispatcher) {
            listOf("NaN", "Infinity", "-Infinity").forEach { typed ->
                val log = FakeLogRepository()
                val viewModel = viewModel(log)
                viewModel.onNameChanged("Dinner")
                viewModel.onProteinChanged(typed)

                assertThat(viewModel.state.value.canSave).isFalse()
                viewModel.save {}
                runCurrent()
                assertThat(log.added).isEmpty()
            }
        }

    @Test
    fun `exactly the bad field is marked, not the whole form`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeLogRepository())
            viewModel.onNameChanged("Dinner")
            viewModel.onCaloriesChanged("500")
            viewModel.onFatChanged("-2")

            assertThat(viewModel.state.value.invalidFields).containsExactly(QuickAddField.FAT)
        }

    @Test
    fun `a named entry with no numbers still saves`() =
        runTest(dispatcher) {
            // "Dinner out, macros unknown" is a real diary entry, and
            // an empty field is absent data rather than wrong data.
            val log = FakeLogRepository()
            val viewModel = viewModel(log)
            viewModel.onNameChanged("Dinner out")

            assertThat(viewModel.state.value.canSave).isTrue()
            viewModel.save {}
            runCurrent()

            assertThat(log.added).hasSize(1)
            assertThat(log.added.single().macros.calories).isEqualTo(0.0)
            assertThat(log.added.single().foodName).isEqualTo("Dinner out")
        }

    @Test
    fun `an entry with no name still cannot be saved`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeLogRepository())
            viewModel.onCaloriesChanged("500")

            assertThat(viewModel.state.value.canSave).isFalse()
        }

    @Test
    fun `an ordinary entry saves what was typed`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val viewModel = viewModel(log)
            viewModel.onNameChanged("Porridge")
            viewModel.onCaloriesChanged("340")
            viewModel.onProteinChanged("12")
            viewModel.onFatChanged("7")
            viewModel.onCarbsChanged("55")

            viewModel.save {}
            runCurrent()

            val macros = log.added.single().macros
            assertThat(macros.calories).isEqualTo(340.0)
            assertThat(macros.proteinG).isEqualTo(12.0)
            assertThat(macros.fatG).isEqualTo(7.0)
            assertThat(macros.carbsG).isEqualTo(55.0)
        }

    @Test
    fun `a very large but possible amount warns and still saves`() =
        runTest(dispatcher) {
            // The whole point of separating impossible from implausible: a whole cake really is
            // 4,000 kcal, and refusing it would be the app overruling someone who knows what they
            // ate.
            val log = FakeLogRepository()
            val viewModel = viewModel(log)
            viewModel.onNameChanged("Entire birthday cake")
            viewModel.onCaloriesChanged("6000")

            assertThat(viewModel.state.value.looksImplausible).isTrue()
            assertThat(viewModel.state.value.canSave).isTrue()

            viewModel.save {}
            runCurrent()
            assertThat(log.added.single().macros.calories).isEqualTo(6000.0)
        }

    @Test
    fun `past-day form keeps its destination and draft when today or diary selection changes`() =
        runTest(dispatcher) {
            val time = MovingTimeProvider(LocalDate.of(2026, 8, 29))
            val diaryDate = CurrentDiaryDate(time)
            diaryDate.set(LocalDate.of(2026, 8, 20))
            val log = FakeLogRepository()
            val vm = viewModel(log, time, diaryDate)
            vm.onNameChanged("Past day")
            vm.onCaloriesChanged("200")
            time.date = LocalDate.of(2026, 8, 30)
            diaryDate.set(time.date)
            assertThat(vm.state.value.targetDate).isEqualTo(LocalDate.of(2026, 8, 20))
            assertThat(vm.state.value.name).isEqualTo("Past day")
            vm.save {}
            runCurrent()
            assertThat(log.added.single().date).isEqualTo(LocalDate.of(2026, 8, 20))
            assertThat(log.added.single().macros.calories).isEqualTo(200.0)
        }

    @Test
    fun `today form saves to its captured date after midnight`() =
        runTest(dispatcher) {
            val time = MovingTimeProvider(LocalDate.of(2026, 8, 29))
            val log = FakeLogRepository()
            val vm = viewModel(log, time)
            vm.onNameChanged("Midnight")
            time.date = LocalDate.of(2026, 8, 30)
            vm.save {}
            runCurrent()
            assertThat(log.added.single().date).isEqualTo(LocalDate.of(2026, 8, 29))
        }

    private class MovingTimeProvider(var date: LocalDate) : TimeProvider {
        override fun today() = date

        override fun now() = Instant.parse("2026-08-30T00:00:00Z")

        override fun currentTime() = LocalTime.MIDNIGHT
    }

    private fun viewModel(
        log: LogRepository,
        time: TimeProvider = FixedTimeProvider,
        diaryDate: CurrentDiaryDate = CurrentDiaryDate(time),
    ) =
        QuickAddViewModel(
            savedStateHandle = SavedStateHandle(),
            logRepository = log,
            timeProvider = time,
            currentDiaryDate = diaryDate,
        )

    private class FakeLogRepository : LogRepository {
        val added = mutableListOf<LogEntry>()

        override suspend fun addEntry(entry: LogEntry): Long {
            added += entry
            return 1L
        }

        override suspend fun toggleFavorite(entry: LogEntry) = error("not used")

        override fun observeIsFavorite(ref: FoodRef): Flow<Boolean> = error("not used")

        override fun observeDay(date: LocalDate): Flow<DayLog> = error("not used")

        override fun observeLoggedDates(): Flow<List<LocalDate>> = error("not used")

        override suspend fun getEntry(id: Long): LogEntry? = error("not used")

        override suspend fun updateEntry(entry: LogEntry) = error("not used")

        override suspend fun clearDay(
            date: java.time.LocalDate
        ): List<com.lethio.macros.domain.model.LogEntry> = error("not used")

        override suspend fun restoreEntries(
            entries: List<com.lethio.macros.domain.model.LogEntry>
        ): Unit = error("not used")

        override suspend fun deleteEntry(id: Long) = error("not used")

        override suspend fun restoreEntry(entry: LogEntry) = error("not used")

        override suspend fun recentFoods(limit: Int): List<LogEntry> = error("not used")

        override suspend fun allEntries(): List<LogEntry> = error("not used")

        override fun observeFavorites(): Flow<List<LogEntry>> = error("not used")
    }

    private object FixedTimeProvider : TimeProvider {
        override fun today(): LocalDate = LocalDate.of(2026, 8, 29)

        override fun now(): Instant = Instant.parse("2026-08-29T09:00:00Z")

        override fun currentTime(): LocalTime = LocalTime.of(9, 0)
    }
}
