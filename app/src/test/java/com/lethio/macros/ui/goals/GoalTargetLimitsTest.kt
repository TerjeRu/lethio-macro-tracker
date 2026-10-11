package com.lethio.macros.ui.goals

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.nutrition.GoalTargetLimits
import org.junit.Test

class GoalTargetLimitsTest {
    @Test
    fun broadCeilingsKeepHighTargetsButRejectTyposAndNonfiniteValues() {
        assertThat(GoalTargetLimits.accepts(Macros(6000.0, 300.0, 150.0, 800.0))).isTrue()
        assertThat(GoalTargetLimits.accepts(Macros(20000.0, 5000.0, 5000.0, 5000.0))).isTrue()
        for (calories in
            listOf(20_000.01, 9_000_000.0, Double.NaN, Double.POSITIVE_INFINITY, 799.99)) {
            assertThat(GoalTargetLimits.accepts(Macros(calories, 150.0, 65.0, 250.0))).isFalse()
        }
        for (grams in listOf(5000.01, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThat(GoalTargetLimits.accepts(Macros(2000.0, grams, 65.0, 250.0))).isFalse()
            assertThat(GoalTargetLimits.accepts(Macros(2000.0, 150.0, grams, 250.0))).isFalse()
            assertThat(GoalTargetLimits.accepts(Macros(2000.0, 150.0, 65.0, grams))).isFalse()
        }
    }

    @Test
    fun blankFieldsCannotInheritInvalidStoredTargetsButExplicitCorrectionCan() {
        val state =
            GoalsUiState(
                calories = "",
                protein = "150",
                fat = "65",
                carbs = "250",
                inheritedTargets = Macros(9_000_000.0, 150.0, 65.0, 250.0),
            )
        assertThat(state.canSave).isFalse()
        assertThat(state.aboveMaxFields).containsExactly(GoalField.CALORIES)
        assertThat(state.copy(calories = "2000").canSave).isTrue()
        val low = state.copy(inheritedTargets = Macros(500.0, 150.0, 65.0, 250.0))
        assertThat(low.canSave).isFalse()
        assertThat(low.caloriesUnsafelyLow).isTrue()
    }
}
