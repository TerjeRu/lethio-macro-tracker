package com.lethio.macros.ui.diary

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.theme.LethioTheme
import java.time.*
import org.junit.Rule
import org.junit.Test

class EntryEditSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun invalidInputExplainsFailureAndPreviewUsesLoggedSnapshot() {
        val entry =
            LogEntry(
                42,
                LocalDate.of(2026, 9, 1),
                MealType.BREAKFAST,
                FoodRef.Bundled(999999),
                "Historical snapshot",
                quantity = Quantity.ofGrams(40.0),
                macros = Macros(150.0, 5.0, 3.0, 27.0),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        compose.setContent {
            var draft by remember { mutableStateOf(EntryEditState(entry)) }
            LethioTheme {
                EntryEditSheet(
                    draft,
                    { draft = draft.copy(amount = it) },
                    { draft = draft.copy(meal = it) },
                    {},
                    {},
                    LocalDate.of(2026, 10, 4),
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("60")
        compose.onNodeWithText("225 kcal").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("-1")
        compose.onNodeWithText("Enter an amount greater than zero.").assertExists()
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.onNodeWithText("Historical snapshot").assertExists()
    }
}
