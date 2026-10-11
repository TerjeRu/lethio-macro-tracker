package com.lethio.macros.ui.diary

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lethio.macros.MainActivity
import com.lethio.macros.R
import com.lethio.macros.SettingsManager
import com.lethio.macros.core.SystemTimeProvider
import com.lethio.macros.data.user.LogRepositoryImpl
import com.lethio.macros.di.DatabaseModule
import com.lethio.macros.domain.model.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Exercise the real MainActivity/AppNavHost and its shared Diary, without a test navigation graph.
 */
class DiaryHistoryNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun historicalDiaryRotationAndBackPreserveHistoryChoicesAndScroll() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clock = SystemTimeProvider()
        val settings = SettingsManager(context)
        val db = DatabaseModule.provideUserDatabase(context)
        val logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
        val day = clock.today().minusDays(2)
        val id = runBlocking {
            settings.setLanguagePreference("en")
            settings.setEnergyUnit(EnergyUnit.KCAL)
            settings.markDatabaseChosen()
            logs.addEntry(
                LogEntry(
                    0,
                    day,
                    MealType.LUNCH,
                    FoodRef.QuickAdd,
                    "Navigation snapshot",
                    quantity = Quantity.ofGrams(40.0),
                    macros = Macros(5000.0, 111.0, 22.0, 333.0),
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                )
            )
        }
        db.close()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var locale = java.util.Locale.getDefault()
                scenario.onActivity { locale = it.resources.configuration.locales[0] }
                compose.waitUntil(5_000) {
                    compose
                        .onAllNodesWithContentDescription("History")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithContentDescription("History").performClick()
                compose.onNodeWithText("28 days").performClick()
                compose.onNode(hasText("Protein") and hasClickAction()).performClick()
                val label =
                    day.format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                    )
                val row = hasText(label) and hasClickAction()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(row)
                val energy = java.text.NumberFormat.getIntegerInstance(locale).format(5000)
                compose
                    .onNode(row)
                    .performScrollTo()
                    .assert(hasText("$energy kcal"))
                    .assert(hasText("111 g"))
                    .performClick()
                compose.onNodeWithText(diaryHeaderDate(day, locale)).assertIsDisplayed()
                compose
                    .onNodeWithContentDescription(
                        context.getString(R.string.calorie_summary_cd, 5000, 2000, 0)
                    )
                    .assertIsDisplayed()
                compose.onNodeWithContentDescription("Previous day").performClick()
                // The header date differs in form from History's rows, so text alone identifies it.
                val dateHeader = hasText(diaryHeaderDate(day.minusDays(1), locale))
                compose.waitUntil(5_000) {
                    compose.onAllNodes(dateHeader).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(dateHeader).assertIsDisplayed()
                scenario.recreate()
                compose.waitUntil(5_000) {
                    compose.onAllNodes(dateHeader).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(dateHeader).assertIsDisplayed()
                // Stay away beyond the observer timeout, as a reader correcting an entry does.
                Thread.sleep(5_500)
                // The Diary header's ordinary History action returns to the retained history entry.
                compose.onNodeWithContentDescription("History").performClick()
                compose.onNode(row).assertIsDisplayed()
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                compose.onNodeWithText("28 days").performScrollTo().assertIsSelected()
                compose
                    .onNode(hasText("Protein") and hasClickAction())
                    .performScrollTo()
                    .assertIsSelected()
            }
        } finally {
            val cleanup = DatabaseModule.provideUserDatabase(context)
            try {
                runBlocking {
                    LogRepositoryImpl(cleanup.dailyLogDao(), cleanup.favoriteDao(), clock)
                        .deleteEntry(id)
                }
            } finally {
                cleanup.close()
            }
        }
    }
}
