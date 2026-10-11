package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Macros
import org.junit.Test

class AtwaterCheckTest {

    @Test
    fun `predicts energy from macros using Atwater factors`() {
        val macros = Macros(calories = 0.0, proteinG = 10.0, fatG = 5.0, carbsG = 20.0)
        // 10*4 + 5*9 + 20*4 = 40 + 45 + 80
        assertThat(AtwaterCheck.predictedCalories(macros)).isWithin(0.001).of(165.0)
    }

    @Test
    fun `accepts a real food whose label rounds`() {
        // Semi-skimmed milk, roughly as labelled.
        val milk = Macros(calories = 50.0, proteinG = 3.6, fatG = 1.8, carbsG = 4.8)
        assertThat(AtwaterCheck.isConsistent(milk)).isTrue()
    }

    @Test
    fun `accepts high-fat food`() {
        val oliveOil = Macros(calories = 884.0, proteinG = 0.0, fatG = 100.0, carbsG = 0.0)
        assertThat(AtwaterCheck.isConsistent(oliveOil)).isTrue()
    }

    @Test
    fun `rejects kilojoules mistakenly stored as kilocalories`() {
        // The most common corruption in branded nutrition data:
        // 2250 kJ recorded in the kcal column for a food that is really about 538 kcal.
        val kjAsKcal = Macros(calories = 2250.0, proteinG = 6.0, fatG = 31.0, carbsG = 57.0)
        assertThat(AtwaterCheck.isConsistent(kjAsKcal)).isFalse()
    }

    @Test
    fun `rejects macros scaled by one hundred`() {
        // The old asset stored macros x100 as integers: Nutella read proteins_100g = 541.
        val scaled = Macros(calories = 539.0, proteinG = 541.0, fatG = 3080.0, carbsG = 5740.0)
        assertThat(AtwaterCheck.isConsistent(scaled)).isFalse()
    }

    @Test
    fun `tolerates low-calorie foods where small absolute differences are proportionally large`() {
        // Lettuce: 15 kcal stated, macros predict about 11. Proportionally 27% out, but
        // meaningless in absolute terms, so the absolute floor must let it through.
        val lettuce = Macros(calories = 15.0, proteinG = 1.4, fatG = 0.2, carbsG = 0.5)
        assertThat(AtwaterCheck.isConsistent(lettuce)).isTrue()
    }

    @Test
    fun `zero macros with zero calories is consistent`() {
        assertThat(AtwaterCheck.isConsistent(Macros.ZERO)).isTrue()
    }

    @Test
    fun `discrepancy is signed so the UI can say which way it is wrong`() {
        val tooHigh = Macros(calories = 500.0, proteinG = 0.0, fatG = 0.0, carbsG = 25.0)
        assertThat(AtwaterCheck.discrepancy(tooHigh)).isGreaterThan(0.0)

        val tooLow = Macros(calories = 50.0, proteinG = 0.0, fatG = 0.0, carbsG = 25.0)
        assertThat(AtwaterCheck.discrepancy(tooLow)).isLessThan(0.0)
    }
}
