package com.lethio.macros.ui.log

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.NutritionBasis
import com.lethio.macros.domain.model.Serving
import com.lethio.macros.domain.repository.ActiveDatabase
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.domain.repository.LogRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A favourite can outlive its food (a rebuild drops the row, or the user deletes a custom food).
 * The screen must tell that apart from a slow load instead of showing "Loading…" forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogEntryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope is hardwired to Dispatchers.Main, which does not exist on the JVM.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a meal chosen on Diary preselects that meal, an unknown one falls back to the time`() {
        // The add button on a meal header carries the meal through Search to here.
        assertThat(viewModel(food = PEANUT_BUTTER, meal = "LUNCH").state.value.mealType)
            .isEqualTo(MealType.LUNCH)
        assertThat(viewModel(food = PEANUT_BUTTER, meal = "BRUNCH").state.value.mealType)
            .isEqualTo(MealType.forTime(FixedTimeProvider.currentTime()))
        assertThat(viewModel(food = PEANUT_BUTTER).state.value.mealType)
            .isEqualTo(MealType.forTime(FixedTimeProvider.currentTime()))
    }

    @Test
    fun `a food that resolves loads normally`() =
        runTest(dispatcher) {
            val viewModel = viewModel(food = PEANUT_BUTTER)

            viewModel.state.test {
                assertThat(awaitItem().load).isEqualTo(LogEntryUiState.Load.LOADING)
                val loaded = awaitItem()
                assertThat(loaded.load).isEqualTo(LogEntryUiState.Load.LOADED)
                assertThat(loaded.food).isEqualTo(PEANUT_BUTTER)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a favourite pointing at a dropped seed food reports MISSING, not LOADING`() =
        runTest(dispatcher) {
            // 1016843698755 is one of the three bundled ids that existed in the v2 seed and does
            // not exist in build 3. Its favourite row survives the rebuild.
            val viewModel = viewModel(food = null, foodId = 1016843698755L)

            viewModel.state.test {
                assertThat(awaitItem().load).isEqualTo(LogEntryUiState.Load.LOADING)
                val settled = awaitItem()
                assertThat(settled.load).isEqualTo(LogEntryUiState.Load.MISSING)
                assertThat(settled.food).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a deleted custom food reports MISSING too`() =
        runTest(dispatcher) {
            val viewModel = viewModel(food = null, foodKind = FoodRef.Kind.CUSTOM.value)

            viewModel.state.test {
                assertThat(awaitItem().load).isEqualTo(LogEntryUiState.Load.LOADING)
                assertThat(awaitItem().load).isEqualTo(LogEntryUiState.Load.MISSING)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * The screen hides the favourite toggle and the log button while [LogEntryUiState.food] is
     * null, so a missing food must not leave a state that looks actionable.
     */
    @Test
    fun `a missing food cannot be saved or favourited`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val viewModel = viewModel(food = null, logRepository = log)
            var saved = false

            viewModel.save { saved = true }
            viewModel.toggleFavorite()

            assertThat(saved).isFalse()
            assertThat(log.added).isEmpty()
            assertThat(log.toggled).isEmpty()
        }

    // ---- the decimal separator ----------------------------------------------------

    /**
     * Seven of eight locales write 1.5 as "1,5" and the Diary displays it that way, so the amount
     * field must accept it back.
     */
    @Test
    fun `an amount typed with a comma logs the same entry as one typed with a dot`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val viewModel = viewModel(food = PEANUT_BUTTER, logRepository = log)
            runCurrent()

            viewModel.onAmountChanged("1,5")
            viewModel.save {}
            runCurrent()

            // Grams, so 1.5 g of peanut butter — the point is the number, not the unit.
            assertThat(log.added).hasSize(1)
            assertThat(log.added.single().quantity.grams).isWithin(0.001).of(1.5)
        }

    @Test
    fun `the preview reads a comma too, so the screen is not red while the value is fine`() =
        runTest(dispatcher) {
            val viewModel = viewModel(food = PEANUT_BUTTER)
            runCurrent()

            viewModel.onAmountChanged("1,5")
            runCurrent()

            assertThat(viewModel.state.value.previewGrams()).isWithin(0.001).of(1.5)
        }

    /**
     * A regression guard, not a new behaviour. `UnitConverter.resolve` calls
     * `NumericInput.isUsable`, so a negative amount is refused here; the point is that routing the
     * string through `NumericInput.parse` does not quietly loosen it.
     */
    @Test
    fun `a negative amount is still refused after the parse path changed`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val viewModel = viewModel(food = PEANUT_BUTTER, logRepository = log)
            runCurrent()

            viewModel.onAmountChanged("-100")
            viewModel.save {}
            runCurrent()

            assertThat(log.added).isEmpty()
            assertThat(viewModel.state.value.previewGrams()).isNull()
        }

    @Test
    fun `an ambiguous amount is refused rather than guessed at`() =
        runTest(dispatcher) {
            val viewModel = viewModel(food = PEANUT_BUTTER)
            runCurrent()

            viewModel.onAmountChanged("1.234,5")

            assertThat(viewModel.state.value.previewGrams()).isNull()
        }

    @Test
    fun `midnight does not move the open form destination or draft`() =
        runTest(dispatcher) {
            val time = MovingTimeProvider(LocalDate.of(2026, 8, 22))
            val diaryDate = CurrentDiaryDate(time)
            val log = FakeLogRepository()
            val vm =
                viewModel(PEANUT_BUTTER, logRepository = log, time = time, diaryDate = diaryDate)
            runCurrent()
            vm.onAmountChanged("60")
            time.date = LocalDate.of(2026, 8, 23)
            diaryDate.set(time.date)
            assertThat(vm.state.value.targetDate).isEqualTo(LocalDate.of(2026, 8, 22))
            assertThat(vm.state.value.amount).isEqualTo("60")
            vm.save {}
            runCurrent()
            assertThat(log.added.single().date).isEqualTo(LocalDate.of(2026, 8, 22))
            assertThat(log.added.single().quantity.grams).isEqualTo(60.0)
        }

    @Test
    fun `native ml preview and save agree without an invented gram weight`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val food =
                PEANUT_BUTTER.copy(
                    per100g = Macros(40.0, 2.0, 1.0, 3.0),
                    nutritionBasis = NutritionBasis.VOLUME,
                    servings = listOf(Serving("cup", 200.0)),
                )
            val vm = viewModel(food, logRepository = log)
            runCurrent()
            assertThat(vm.state.value.unit).isEqualTo(MeasureUnit.Milliliters)
            assertThat(vm.state.value.amount).isEqualTo("100")
            vm.onAmountChanged("250")
            val preview = vm.state.value.previewNutrition()
            assertThat(preview).isEqualTo(Macros(100.0, 5.0, 2.5, 7.5))
            vm.save {}
            runCurrent()
            val saved = log.added.single()
            assertThat(saved.macros).isEqualTo(preview)
            assertThat(saved.quantity.grams).isNull()
            assertThat(saved.quantity.basisAmount).isEqualTo(250.0)
            vm.onUnitChanged(MeasureUnit.Grams)
            assertThat(vm.state.value.previewNutrition()).isNull()
            vm.save {}
            runCurrent()
            assertThat(log.added).hasSize(1)
        }

    @Test
    fun `density backed mass uses the same native volume calculator in preview and save`() =
        runTest(dispatcher) {
            val log = FakeLogRepository()
            val food =
                PEANUT_BUTTER.copy(
                    per100g = Macros(40.0),
                    nutritionBasis = NutritionBasis.VOLUME,
                    densityGPerMl = 0.8,
                )
            val vm = viewModel(food, logRepository = log)
            runCurrent()
            vm.onUnitChanged(MeasureUnit.Grams)
            vm.onAmountChanged("200")
            assertThat(vm.state.value.previewNutrition()?.calories).isEqualTo(100.0)
            vm.save {}
            runCurrent()
            assertThat(log.added.single().macros.calories).isEqualTo(100.0)
            assertThat(log.added.single().quantity.grams).isEqualTo(200.0)
            assertThat(log.added.single().quantity.basisAmount).isEqualTo(250.0)
        }

    private class MovingTimeProvider(var date: LocalDate) : TimeProvider {
        override fun today() = date

        override fun now() = Instant.parse("2026-08-23T00:00:00Z")

        override fun currentTime() = LocalTime.MIDNIGHT
    }

    private fun viewModel(
        food: Food?,
        foodId: Long = 1L,
        foodKind: String = FoodRef.Kind.BUNDLED.value,
        logRepository: LogRepository = FakeLogRepository(),
        time: TimeProvider = FixedTimeProvider,
        diaryDate: CurrentDiaryDate = CurrentDiaryDate(time),
        meal: String? = null,
    ) =
        LogEntryViewModel(
            savedStateHandle =
                SavedStateHandle(
                    mapOf(
                        LogEntryViewModel.ARG_FOOD_ID to foodId,
                        LogEntryViewModel.ARG_FOOD_KIND to foodKind,
                    ) + (meal?.let { mapOf(LogEntryViewModel.ARG_MEAL to it) } ?: emptyMap())
                ),
            foodRepository = FakeFoodRepository(food),
            logRepository = logRepository,
            timeProvider = time,
            currentDiaryDate = diaryDate,
        )

    private class FakeFoodRepository(private val food: Food?) : FoodRepository {
        override suspend fun search(query: String, limit: Int): List<Food> = emptyList()

        override suspend fun findByBarcode(barcode: String): Food? = null

        override suspend fun getByRef(ref: FoodRef): Food? = food

        override suspend fun saveCustomFood(food: Food): FoodRef = error("not used")

        override suspend fun deleteCustomFood(ref: FoodRef.Custom) = error("not used")

        override suspend fun activeDatabase(): ActiveDatabase? = null
    }

    private class FakeLogRepository : LogRepository {
        val added = mutableListOf<LogEntry>()
        val toggled = mutableListOf<LogEntry>()

        override suspend fun addEntry(entry: LogEntry): Long {
            added += entry
            return 1L
        }

        override suspend fun toggleFavorite(entry: LogEntry) {
            toggled += entry
        }

        override fun observeIsFavorite(ref: FoodRef): Flow<Boolean> = flowOf(false)

        override fun observeFavorites(): Flow<List<LogEntry>> = flowOf(emptyList())

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
    }

    private object FixedTimeProvider : TimeProvider {
        override fun today(): LocalDate = LocalDate.of(2026, 8, 22)

        override fun now(): Instant = Instant.parse("2026-08-22T09:00:00Z")

        override fun currentTime(): LocalTime = LocalTime.of(9, 0)
    }

    private companion object {
        val PEANUT_BUTTER =
            Food(
                ref = FoodRef.Bundled(1L),
                name = "Peanut Butter",
                per100g = Macros(calories = 588.0, proteinG = 25.0, fatG = 50.0, carbsG = 20.0),
            )
    }
}
