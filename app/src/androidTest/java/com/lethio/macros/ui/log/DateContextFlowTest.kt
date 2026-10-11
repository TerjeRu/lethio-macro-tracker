package com.lethio.macros.ui.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.R
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.user.LogRepositoryImpl
import com.lethio.macros.data.user.UserDatabase
import com.lethio.macros.domain.model.*
import com.lethio.macros.domain.repository.ActiveDatabase
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.ui.theme.LethioTheme
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real forms save through the shipped Room driver; food resolution alone is synthetic. */
class DateContextFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ViewModelStore()
    private val time = MovingTime()
    private val destination = LocalDate.of(2026, 9, 1)
    private lateinit var db: UserDatabase
    private lateinit var logs: LogRepositoryImpl

    private fun open() =
        Room.databaseBuilder(context, UserDatabase::class.java, "date-context-test.db")
            .setDriver(BundledSQLiteDriver())
            .build()

    @Before
    fun setup() {
        context.deleteDatabase("date-context-test.db")
        db = open()
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), time)
    }

    @After
    fun cleanup() {
        store.clear()
        db.close()
        context.deleteDatabase("date-context-test.db")
    }

    @Test fun searchFoodFormDisplaysTheDateItPersistsAfterMidnight() = foodForm(FoodRef.Bundled(42))

    @Test fun scanCustomFoodFormUsesTheSameCapturedDestination() = foodForm(FoodRef.Custom(42))

    private fun foodForm(ref: FoodRef) {
        val selected = CurrentDiaryDate(time).apply { set(destination) }
        val food = Food(ref, "Synthetic food", per100g = Macros(200.0, 10.0, 4.0, 30.0))
        val vm =
            LogEntryViewModel(
                SavedStateHandle(
                    mapOf(
                        LogEntryViewModel.ARG_FOOD_ID to ref.id,
                        LogEntryViewModel.ARG_FOOD_KIND to ref.kind.value,
                    )
                ),
                SyntheticFoodRepository(food),
                logs,
                time,
                selected,
            )
        store.put("form", vm)
        val done = AtomicBoolean(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, 2f),
            ) {
                LethioTheme(darkTheme = true) { LogEntryScreen({ done.set(true) }, {}, vm) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.food != null }
        compose.runOnIdle {
            vm.onAmountChanged("60")
            time.date = time.date.plusDays(1)
            selected.set(time.date)
        }
        val label =
            context.getString(
                R.string.diary_add_to_date,
                destination.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose
            .onNode(hasText(context.getString(R.string.log_food)) and hasClickAction())
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) { done.get() }
        val saved = reopenEntry()
        assertThat(saved.date).isEqualTo(destination)
        assertThat(saved.foodRef).isEqualTo(ref)
        assertThat(saved.quantity.grams).isEqualTo(60.0)
        assertThat(saved.macros.calories).isEqualTo(120.0)
    }

    @Test
    fun quickAddFormDisplaysItsDestinationAndCancelDoesNotWrite() {
        val selected = CurrentDiaryDate(time).apply { set(destination) }
        val vm = QuickAddViewModel(SavedStateHandle(), logs, time, selected)
        store.put("form", vm)
        val done = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, 2f),
            ) {
                LethioTheme(darkTheme = true) {
                    QuickAddScreen({ done.set(true) }, { cancelled.set(true) }, vm)
                }
            }
        }
        compose.runOnIdle {
            vm.onNameChanged("Synthetic quick add")
            vm.onCaloriesChanged("240")
            time.date = time.date.plusDays(1)
            selected.set(time.date)
        }
        val label =
            context.getString(
                R.string.diary_add_to_date,
                destination.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back_cd)).performClick()
        assertThat(cancelled.get()).isTrue()
        assertThat(runBlocking { logs.allEntries() }).isEmpty()
        // The callback is observed without removing the composition, then the real Save is tested.
        compose
            .onNode(hasText(context.getString(R.string.log_food)) and hasClickAction())
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) { done.get() }
        val saved = reopenEntry()
        assertThat(saved.date).isEqualTo(destination)
        assertThat(saved.macros.calories).isEqualTo(240.0)
    }

    private fun reopenEntry(): LogEntry {
        store.clear()
        db.close()
        db = open()
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), time)
        return runBlocking {
            logs.observeDay(destination).first().entriesByMeal.values.flatten().single()
        }
    }

    private class MovingTime : TimeProvider {
        var date = LocalDate.of(2026, 10, 4)

        override fun today() = date

        override fun now() = Instant.parse("2026-10-05T00:00:00Z")

        override fun currentTime() = LocalTime.NOON
    }

    private class SyntheticFoodRepository(private val food: Food) : FoodRepository {
        override suspend fun getByRef(ref: FoodRef) = food.takeIf { it.ref == ref }

        override suspend fun search(query: String, limit: Int) = listOf(food)

        override suspend fun findByBarcode(barcode: String): Food? = null

        override suspend fun saveCustomFood(food: Food): FoodRef = error("not used")

        override suspend fun deleteCustomFood(ref: FoodRef.Custom) = error("not used")

        override suspend fun activeDatabase(): ActiveDatabase? = null
    }
}
