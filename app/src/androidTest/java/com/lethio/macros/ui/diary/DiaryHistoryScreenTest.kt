package com.lethio.macros.ui.diary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.user.*
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.theme.LethioTheme
import com.lethio.macros.ui.util.LocalEnergyUnit
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Render the shipped view over real bounded Room reads, including gaps and saved goals. */
class DiaryHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 10, 4)
    private val start = today.minusDays(6)
    private val store = ViewModelStore()
    private val clock =
        object : TimeProvider {
            override fun today() = today

            override fun now() = Instant.EPOCH

            override fun currentTime() = LocalTime.NOON
        }
    private lateinit var db: UserDatabase
    private lateinit var logs: LogRepositoryImpl
    private lateinit var goals: GoalsRepositoryImpl

    @Before
    fun setup() {
        context.deleteDatabase("history-screen-test.db")
        db =
            Room.databaseBuilder(context, UserDatabase::class.java, "history-screen-test.db")
                .setDriver(BundledSQLiteDriver())
                .build()
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
        goals = GoalsRepositoryImpl(db.goalsDao(), clock)
    }

    @After
    fun cleanup() {
        store.clear()
        db.close()
        context.deleteDatabase("history-screen-test.db")
    }

    private fun dateLabel(date: LocalDate) =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    private fun number(value: Double, decimals: Int = 0) =
        java.text.NumberFormat.getNumberInstance(context.resources.configuration.locales[0])
            .apply { maximumFractionDigits = decimals }
            .format(value)

    private fun row(date: LocalDate): SemanticsNodeInteraction {
        val matcher = hasText(dateLabel(date)) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun vm() =
        DiaryHistoryViewModel(
                DiaryHistoryRepositoryImpl(db.dailyLogDao(), db.goalsDao()),
                clock,
                SavedStateHandle(mapOf("historyDate" to today.toString())),
            )
            .also { store.put("history", it) }

    private fun add(date: LocalDate, macros: Macros) = runBlocking {
        logs.addEntry(
            LogEntry(
                0,
                date,
                MealType.LUNCH,
                FoodRef.QuickAdd,
                "Synthetic snapshot",
                quantity = Quantity.ofGrams(40.0),
                macros = macros,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        )
    }

    @Test
    fun emptyRowsOpenTheirExplicitDatesWithoutInventingIntakeOrGoals() {
        val vm = vm()
        var opened: LocalDate? = null
        compose.setContent { LethioTheme { DiaryHistoryScreen({}, { opened = it }, vm) } }
        compose.waitUntil(5_000) { !vm.uiState.value.loading }
        row(start)
            .performScrollTo()
            .assert(hasText(context.getString(R.string.diary_history_no_entries)))
        row(start).performClick()
        assertThat(opened).isEqualTo(start)
        assertThat(vm.uiState.value.days.all { it.loggedIntake == null && it.savedGoal == null })
            .isTrue()
    }

    @Test
    fun rowsShowAggregatedSnapshotsLoggedZeroAndHistoricallyChangedTargetsInKilojoules() {
        add(start, Macros(150.25, 5.0, 3.0, 27.0))
        add(start, Macros(49.75, 2.0, 1.0, 10.0))
        add(start.plusDays(2), Macros.ZERO)
        add(today, Macros(620.0, 30.0, 20.0, 80.0))
        runBlocking {
            goals.setGoals(
                Goals(start.plusDays(1), Macros(1800.0, 100.0, 60.0, 220.0), Goals.Source.MANUAL)
            )
            goals.setGoals(
                Goals(start.plusDays(4), Macros(2200.0, 140.0, 70.0, 250.0), Goals.Source.MANUAL)
            )
        }
        val vm = vm()
        compose.setContent {
            CompositionLocalProvider(LocalEnergyUnit provides EnergyUnit.KJ) {
                LethioTheme { DiaryHistoryScreen({}, {}, vm) }
            }
        }
        compose.waitUntil(5_000) { !vm.uiState.value.loading }
        row(start)
            .performScrollTo()
            .assert(hasText("837 kJ"))
            .assert(hasText("7 g"))
            .assert(hasText("37 g"))
            .assert(hasText("4 g"))
        // Saved targets are always shown; the first day predates the first saved goal.
        row(start)
            .performScrollTo()
            .assert(hasText(context.getString(R.string.daily_target), substring = true).not())
        // Rows carry values only; targets live in the chart line and the summary.
        val target = hasText(context.getString(R.string.daily_target), substring = true)
        row(start.plusDays(2)).performScrollTo().assert(hasText("0 kJ")).assert(target.not())
        row(today).performScrollTo().assert(hasText("${number(2594.0)} kJ")).assert(target.not())
        val diary = runBlocking { logs.observeDay(start).first() }
        assertThat(diary.totals).isEqualTo(vm.uiState.value.days.first().loggedIntake)
    }

    @Test
    fun largeTextRtlDarkRowsKeepLongDatesLargeValuesAndTargetControlsReachable() {
        add(today, Macros(3_000_000_000.0, 12345.6, 999.0, 4567.8))
        val vm = vm()
        var opened: LocalDate? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LethioTheme(darkTheme = true) { DiaryHistoryScreen({}, { opened = it }, vm) }
            }
        }
        compose.waitUntil(5_000) { !vm.uiState.value.loading }
        compose
            .onNodeWithText(context.getString(R.string.diary_history_28_days))
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) { vm.uiState.value.days.size == 28 }
        row(today)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasText("${number(3_000_000_000.0)} kcal"))
            .assert(hasText("${number(12345.6, 1)} g"))
        row(today).performClick()
        assertThat(opened).isEqualTo(today)
        assertThat(vm.uiState.value.days.all { it.savedGoal == null }).isTrue()
    }

    @Test
    fun failedReadIsNotPresentedAsNoEntriesAndRetryIsAnOrdinaryControl() {
        val state =
            mutableStateOf(
                DiaryHistoryUiState(
                    DiaryHistoryWindow(today, DiaryHistoryPeriod.SEVEN_DAYS),
                    loading = false,
                    failed = true,
                )
            )
        var retried = false
        compose.setContent {
            LethioTheme {
                DiaryHistoryContent(
                    state.value,
                    {},
                    {},
                    {},
                    {},
                    {
                        retried = true
                        state.value =
                            state.value.copy(
                                failed = false,
                                days = listOf(DiaryHistoryDay(today, 0, null, null)),
                            )
                    },
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.diary_history_failed))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.diary_history_no_entries))
            .assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.diary_history_retry))
            .performScrollTo()
            .performClick()
        assertThat(retried).isTrue()
        row(today)
            .performScrollTo()
            .assert(hasText(context.getString(R.string.diary_history_no_entries)))
    }
}
