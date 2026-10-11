package com.lethio.macros.ui.diary

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.user.*
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.theme.LethioTheme
import com.lethio.macros.ui.util.LocalEnergyUnit
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*

class MealSubtotalTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 10, 4)
    private val clock =
        object : TimeProvider {
            override fun today() = day

            override fun now() = Instant.EPOCH

            override fun currentTime() = LocalTime.NOON
        }
    private lateinit var db: UserDatabase
    private lateinit var logs: LogRepositoryImpl
    private val store = ViewModelStore()

    @Before
    fun setup() {
        context.deleteDatabase("subtotal-test.db")
        db =
            Room.databaseBuilder(context, UserDatabase::class.java, "subtotal-test.db")
                .setDriver(BundledSQLiteDriver())
                .build()
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
    }

    @After
    fun cleanup() {
        store.clear()
        db.close()
        context.deleteDatabase("subtotal-test.db")
    }

    private fun add(meal: MealType, macros: Macros) = runBlocking {
        logs.addEntry(
            LogEntry(
                0,
                day,
                meal,
                FoodRef.QuickAdd,
                "Synthetic snapshot",
                quantity = Quantity.ofGrams(40.0),
                macros = macros,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        )
    }

    private fun energy(value: String) = compose.onNode(hasText(value, substring = true))

    @Test
    fun storedSnapshotsReactToCorrectionMealMoveDeleteAndUndo() {
        add(MealType.BREAKFAST, Macros(100.2, 5.0, 2.0, 10.0))
        add(MealType.BREAKFAST, Macros(100.2, 7.0, 3.0, 20.0))
        val vm =
            DiaryViewModel(
                logs,
                GoalsRepositoryImpl(db.goalsDao(), clock),
                clock,
                CurrentDiaryDate(clock),
            )
        store.put("diary", vm)
        compose.setContent {
            LethioTheme {
                val state by vm.uiState.collectAsState()
                Column {
                    MealType.ordered.forEach { MealSubtotal(state.day.entriesByMeal[it].orEmpty()) }
                }
            }
        }
        compose.waitUntil(5000) {
            vm.uiState.value.day.entriesByMeal[MealType.BREAKFAST]?.size == 2
        }
        energy("200 kcal").assertExists()
        compose
            .onNode(
                hasText(
                    "\u00B7 ${context.getString(com.lethio.macros.R.string.protein)}",
                    substring = true,
                )
            )
            .assertExists()
        val original = vm.uiState.value.day.entriesByMeal.getValue(MealType.BREAKFAST).first()
        compose.runOnIdle {
            vm.editEntry(original)
            vm.editAmount("80")
            vm.editMeal(MealType.LUNCH)
            vm.saveEdit()
        }
        compose.waitUntil(5000) { vm.uiState.value.day.entriesByMeal[MealType.LUNCH]?.size == 1 }
        energy("100 kcal").assertExists()
        energy("200 kcal").assertExists()
        assertThat(vm.uiState.value.day.totals.calories).isWithin(0.00001).of(300.6)
        val moved = vm.uiState.value.day.entriesByMeal.getValue(MealType.LUNCH).single()
        compose.runOnIdle { vm.deleteLog(moved) }
        compose.waitUntil(5000) {
            vm.lastDeleted.value != null &&
                vm.uiState.value.day.entriesByMeal[MealType.LUNCH].isNullOrEmpty()
        }
        energy("200 kcal").assertDoesNotExist()
        compose.runOnIdle { vm.undoDelete() }
        compose.waitUntil(5000) {
            vm.lastDeleted.value == null &&
                vm.uiState.value.day.entriesByMeal[MealType.LUNCH]?.size == 1
        }
        energy("200 kcal").assertExists()
        assertThat(vm.uiState.value.day.totals.calories).isWithin(0.00001).of(300.6)
    }

    @Test
    fun emptyZeroKilojoulesAndRtlLargeFontUseOrdinaryReadableText() {
        val unit = mutableStateOf(EnergyUnit.KCAL)
        val entries = mutableStateOf(emptyList<LogEntry>())
        compose.setContent {
            CompositionLocalProvider(
                LocalEnergyUnit provides unit.value,
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LethioTheme(darkTheme = true) { MealSubtotal(entries.value) }
            }
        }
        energy("0 kcal").assertDoesNotExist()
        val zero =
            LogEntry(
                1,
                day,
                MealType.LUNCH,
                FoodRef.QuickAdd,
                "Zero",
                quantity = Quantity.ofGrams(0.0),
                macros = Macros.ZERO,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        compose.runOnIdle { entries.value = listOf(zero) }
        energy("0 kcal").assertExists()
        compose.runOnIdle {
            unit.value = EnergyUnit.KJ
            entries.value =
                listOf(
                    zero.copy(macros = Macros(100.3, 7.0, 2.0, 12.0)),
                    zero.copy(id = 2, macros = Macros(100.3, 3.0, 1.0, 8.0)),
                )
        }
        energy("839 kJ").assertIsDisplayed()
        compose.onNode(hasText("10 g", substring = true)).assertExists()
        compose.onNode(hasText("20 g", substring = true)).assertExists()
        compose.onNode(hasText("3 g", substring = true)).assertExists()
    }
}
