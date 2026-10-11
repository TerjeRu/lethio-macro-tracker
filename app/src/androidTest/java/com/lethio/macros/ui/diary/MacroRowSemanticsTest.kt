package com.lethio.macros.ui.diary

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lethio.macros.ui.theme.LethioTheme
import com.lethio.macros.ui.theme.macroColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The macro bars must announce a label and a value.
 *
 * `MacroRow` carries a `contentDescription` and a `stateDescription` under
 * `semantics(mergeDescendants = true)`; without them the bars are silent to TalkBack. Lint's
 * accessibility checks cover View/XML only, so nothing else would notice. Drives `MacroRow`
 * directly, without Hilt or a database.
 */
@RunWith(AndroidJUnit4::class)
class MacroRowSemanticsTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun macroRowAnnouncesItsLabelAndItsValue() {
        compose.setContent {
            LethioTheme {
                val macros = MaterialTheme.macroColors
                MacroRow(
                    label = "Protein",
                    current = 30.0,
                    target = 120.0,
                    color = macros.protein,
                    trackColor = macros.proteinContainer,
                )
            }
        }

        // The label is the contentDescription, and mergeDescendants collapses the row to one
        // focus stop -- so a screen reader reaches exactly one node here, not three.
        compose
            .onNodeWithContentDescription("Protein")
            .assertIsDisplayed()
            .assert(hasStateDescription("90 g left, 30 of 120 grams"))
    }

    @Test
    fun aZeroTargetStillAnnouncesAValueRatherThanCrashing() {
        // target = 0 is reachable: a goal row exists before any target is set, and the progress
        // calculation guards against the divide. The announcement should still say something.
        compose.setContent {
            LethioTheme {
                val macros = MaterialTheme.macroColors
                MacroRow(
                    label = "Fat",
                    current = 0.0,
                    target = 0.0,
                    color = macros.fat,
                    trackColor = macros.fatContainer,
                )
            }
        }

        compose
            .onNodeWithContentDescription("Fat")
            .assert(hasStateDescription("0 g left, 0 of 0 grams"))
    }

    private fun hasStateDescription(expected: String) =
        SemanticsMatcher("stateDescription is '$expected'") { node ->
            node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null } == expected
        }
}
