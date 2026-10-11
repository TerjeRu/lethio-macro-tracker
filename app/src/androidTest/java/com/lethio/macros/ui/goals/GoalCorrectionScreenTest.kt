package com.lethio.macros.ui.goals

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.R
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toEntity
import com.lethio.macros.data.user.*
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.diary.DiaryScreen
import com.lethio.macros.ui.diary.DiaryViewModel
import com.lethio.macros.ui.theme.LethioTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class GoalCorrectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 9, 30)
    private val store = ViewModelStore()
    private val clock = object : TimeProvider {
        override fun today() = LocalDate.of(2026, 10, 5)
        override fun now() = Instant.EPOCH
        override fun currentTime() = LocalTime.NOON
    }
    private lateinit var db: UserDatabase
    private lateinit var goals: GoalsRepositoryImpl
    private lateinit var logs: LogRepositoryImpl
    @Before fun setup() {
        context.deleteDatabase("correction-screen.db")
        db = Room.databaseBuilder(context, UserDatabase::class.java, "correction-screen.db")
            .setDriver(BundledSQLiteDriver()).build()
        goals = GoalsRepositoryImpl(db.goalsDao(), clock)
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
        runBlocking {
            goals.setGoals(Goals(date.minusDays(3), Macros(1800.0,100.0,60.0,220.0), Goals.Source.MANUAL))
            db.goalsDao().saveForDate(Goals(date, Macros(9000000.0,150.0,65.0,250.0), Goals.Source.MANUAL).toEntity(clock.now()))
            goals.setGoals(Goals(date.plusDays(3), Macros(2300.0,140.0,70.0,250.0), Goals.Source.MANUAL))
            logs.addEntry(LogEntry(0,date,MealType.LUNCH,FoodRef.QuickAdd,"Synthetic lunch",quantity=Quantity.ofGrams(100.0),
                macros=Macros(334.0,9.0,9.0,54.0),createdAt=clock.now(),updatedAt=clock.now()))
        }
    }
    @After fun cleanup() { store.clear(); db.close(); context.deleteDatabase("correction-screen.db") }
    private fun text(id: Int) = context.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun savedVm() = SavedGoalsViewModel(goals, SavedStateHandle(mapOf("goalsDate" to date.toString())))
        .also { store.put("goals",it) }

    @Test fun invalidHistoricalGoalCanBeCorrectedFromRangeEditorAtLargeFont() {
        val vm = savedVm()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            LethioTheme { SavedGoalsScreen({}, vm) }
        } }
        compose.waitUntil(5000) { vm.state.value.editing != null }
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        compose.onAllNodesWithText(context.getString(R.string.goals_range,date.format(formatter),date.plusDays(2).format(formatter)))
            .onLast().assertExists()
        compose.onNodeWithText("9000000").performTextReplacement("20001")
        screenshot("goal-correction-large-text")
        compose.onNodeWithText(text(R.string.diary_save_changes)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("20001").performScrollTo().performTextReplacement("2000")
        compose.onNodeWithText(text(R.string.diary_save_changes)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { vm.state.value.editing == null }
        runBlocking {
            assertThat(goals.goalsFor(date.plusDays(2)).targets.calories).isEqualTo(2000.0)
            assertThat(goals.goalsFor(date.plusDays(3)).targets.calories).isEqualTo(2300.0)
            assertThat(logs.allEntries()).hasSize(1)
        }
    }

    @Test fun deleteRequiresConfirmationAndFoodSurvives() {
        val vm = savedVm()
        compose.setContent { LethioTheme { SavedGoalsScreen({}, vm) } }
        compose.waitUntil(5000) { vm.state.value.editing != null }
        compose.onNodeWithText(text(R.string.cancel)).performScrollTo().performClick()
        compose.onAllNodesWithText(text(R.string.delete_cd))[1].performClick()
        compose.onNodeWithText(text(R.string.delete_goals_title)).assertIsDisplayed()
        runBlocking { assertThat(goals.goalsFor(date).targets.calories).isEqualTo(9000000.0) }
        compose.onAllNodesWithText(text(R.string.delete_cd)).onLast().performClick()
        compose.waitUntil(5000) { vm.state.value.deleting == null }
        runBlocking {
            assertThat(goals.goalsFor(date).targets.calories).isEqualTo(1800.0)
            assertThat(logs.allEntries()).hasSize(1)
        }
    }

    @Test fun diaryActionsPassHistoricalDateAndClearHasConfirmationAndUndo() {
        val vm = DiaryViewModel(logs,goals,clock,CurrentDiaryDate(clock))
        store.put("diary",vm)
        vm.goToDate(date)
        var opened: LocalDate? = null
        compose.setContent { LethioTheme { DiaryScreen({},vm,onEditGoals={opened=it}) } }
        compose.waitUntil(5000) { vm.uiState.value.day.entriesByMeal.values.flatten().size == 1 }
        compose.onNodeWithContentDescription(text(R.string.day_options)).performClick()
        compose.onNodeWithText(text(R.string.edit_saved_goals)).performClick()
        assertThat(opened).isEqualTo(date)
        compose.onNodeWithContentDescription(text(R.string.day_options)).performClick()
        compose.onNodeWithText(text(R.string.clear_entries)).performClick()
        compose.onNodeWithText(text(R.string.clear_entries_note)).assertIsDisplayed()
        screenshot("clear-day-confirmation")
        compose.onNodeWithText(text(R.string.clear_entries)).performClick()
        compose.waitUntil(5000) { vm.lastDeleted.value != null }
        compose.onNodeWithText(text(R.string.undo)).performClick()
        compose.waitUntil(5000) { vm.lastDeleted.value == null }
        runBlocking {
            assertThat(logs.allEntries()).hasSize(1)
            assertThat(goals.goalsFor(date).targets.calories).isEqualTo(9000000.0)
        }
    }
}
