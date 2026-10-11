package com.lethio.macros.ui.diary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.R
import com.lethio.macros.ui.theme.LethioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Rule
import org.junit.Test

class DiaryDateHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeTextRtlHeaderHasAVisibleDateAndExplicitReturnAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shown = mutableStateOf(LocalDate.of(2025, 9, 28))
        val today = LocalDate.of(2026, 10, 4)
        var returned = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LethioTheme(darkTheme = true) {
                    DiaryDateHeader(
                        shown.value,
                        shown.value == today,
                        {},
                        {},
                        {},
                        {
                            returned = true
                            shown.value = today
                        },
                        {},
                    )
                }
            }
        }
        // No separate "Diary" title; the date (with its year, outside the current year)
        // is the heading and opens the calendar.
        val date =
            diaryHeaderDate(LocalDate.of(2025, 9, 28), context.resources.configuration.locales[0])
        compose.onNodeWithText(date).assertIsDisplayed().assert(hasClickAction())
        compose
            .onNodeWithText(context.getString(R.string.diary_back_to_today))
            .assertIsDisplayed()
            .performClick()
        assertThat(returned).isTrue()
        compose.onNodeWithText(context.getString(R.string.diary_back_to_today)).assertDoesNotExist()
        compose
            .onNodeWithContentDescription(context.getString(R.string.next_day_cd))
            .assertIsNotEnabled()
    }

    @Test
    fun editorContextReclassifiesTheOriginalDateWithoutReplacingTheDraft() {
        val originalDate = LocalDate.of(2026, 10, 4)
        val today = mutableStateOf(originalDate)
        val entry =
            com.lethio.macros.domain.model.LogEntry(
                42,
                originalDate,
                com.lethio.macros.domain.model.MealType.BREAKFAST,
                com.lethio.macros.domain.model.FoodRef.QuickAdd,
                "Snapshot",
                quantity = com.lethio.macros.domain.model.Quantity.ofGrams(40.0),
                macros = com.lethio.macros.domain.model.Macros(150.0, 5.0, 3.0, 27.0),
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            )
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, 2f),
            ) {
                LethioTheme(darkTheme = true) {
                    EntryEditSheet(
                        EntryEditState(entry, amount = "60"),
                        {},
                        {},
                        {},
                        {},
                        today.value,
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val date = originalDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        compose.onNodeWithText("${context.getString(R.string.today_label)} · $date").assertExists()
        compose.runOnIdle { today.value = originalDate.plusDays(1) }
        compose
            .onNodeWithText(context.getString(R.string.diary_past_entry_date, date))
            .assertIsDisplayed()
        compose
            .onNode(hasSetTextAction())
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString("60"),
                )
            )
        compose.onNodeWithText("225 kcal").assertExists()
        compose
            .onNodeWithText(context.getString(R.string.diary_save_changes))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
