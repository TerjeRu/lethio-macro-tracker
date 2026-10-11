package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Serving
import org.junit.Test

class UnitConverterTest {

    private val bread =
        Food(
            ref = FoodRef.Bundled(1),
            name = "Wholemeal bread",
            per100g = Macros(calories = 247.0, proteinG = 13.0, fatG = 3.4, carbsG = 41.0),
            servings = listOf(Serving("slice", 38.0, isDefault = true)),
        )

    private val oil =
        Food(
            ref = FoodRef.Bundled(2),
            name = "Olive oil",
            per100g = Macros(calories = 884.0, fatG = 100.0),
            densityGPerMl = 0.918,
        )

    @Test
    fun `grams pass through unchanged`() {
        val q = UnitConverter.resolve(150.0, MeasureUnit.Grams, bread)
        assertThat(q!!.grams).isWithin(0.001).of(150.0)
    }

    @Test
    fun `named portion multiplies by its gram weight`() {
        val slice = MeasureUnit.Portion("slice", 38.0)
        val q = UnitConverter.resolve(2.0, slice, bread)
        assertThat(q!!.grams).isWithin(0.001).of(76.0)
    }

    @Test
    fun `fractional portions work`() {
        val slice = MeasureUnit.Portion("slice", 38.0)
        assertThat(UnitConverter.resolve(1.5, slice, bread)!!.grams).isWithin(0.001).of(57.0)
    }

    @Test
    fun `ounces convert using the international avoirdupois ounce`() {
        val q = UnitConverter.resolve(4.0, MeasureUnit.Ounces, bread)
        assertThat(q!!.grams).isWithin(0.001).of(113.398092)
    }

    @Test
    fun `millilitres use the food's own density`() {
        val q = UnitConverter.resolve(15.0, MeasureUnit.Milliliters, oil)
        assertThat(q!!.grams).isWithin(0.001).of(13.77)
    }

    @Test
    fun `millilitres are refused when density is unknown`() {
        // Assuming water density here would misreport oil's calories by about 15%, silently.
        // Returning null forces the UI not to offer the unit at all.
        assertThat(UnitConverter.resolve(15.0, MeasureUnit.Milliliters, bread)).isNull()
    }

    @Test
    fun `the quantity keeps what the user typed alongside the resolved mass`() {
        val slice = MeasureUnit.Portion("slice", 38.0)
        val q = UnitConverter.resolve(2.0, slice, bread)!!
        // This is the whole point of the two-form Quantity: editing must reopen showing
        // "2 slices", not a back-converted "76 g" the user never entered.
        assertThat(q.amount).isEqualTo(2.0)
        assertThat(q.unit).isEqualTo(slice)
        assertThat(q.grams).isWithin(0.001).of(76.0)
    }

    @Test
    fun `negative and non-finite amounts are refused`() {
        assertThat(UnitConverter.resolve(-1.0, MeasureUnit.Grams, bread)).isNull()
        assertThat(UnitConverter.resolve(Double.NaN, MeasureUnit.Grams, bread)).isNull()
        assertThat(UnitConverter.resolve(Double.POSITIVE_INFINITY, MeasureUnit.Grams, bread))
            .isNull()
    }

    @Test
    fun `round trips back to the original amount`() {
        val slice = MeasureUnit.Portion("slice", 38.0)
        val grams = requireNotNull(UnitConverter.resolve(3.0, slice, bread)!!.grams)
        assertThat(UnitConverter.fromGrams(grams, slice, bread)).isWithin(0.001).of(3.0)
    }

    @Test
    fun `a portion whose name no longer exists degrades to grams rather than failing`() {
        // The food database gets replaced periodically. A logged entry's gram mass was
        // snapshotted at log time and stays correct, so this must not throw.
        val unit = MeasureUnit.fromLabel("slice", servings = emptyList())
        assertThat(unit).isEqualTo(MeasureUnit.Grams)
    }

    @Test
    fun `a logged entry rebuilds its own portion without needing the food`() {
        // A diary row records amount and resolved mass, so one portion weighs grams / amount.
        // Before this, "1 cup, mashed" (225 g) displayed as "1 g" because the label was resolved
        // against an empty serving list and fell back to grams.
        val unit = MeasureUnit.fromLoggedEntry("cup, mashed", amount = 1.0, grams = 225.0)
        assertThat(unit).isEqualTo(MeasureUnit.Portion("cup, mashed", 225.0))

        val two = MeasureUnit.fromLoggedEntry("slice", amount = 2.0, grams = 76.0)
        assertThat(two).isEqualTo(MeasureUnit.Portion("slice", 38.0))
    }

    @Test
    fun `a logged entry with a zero amount degrades to grams rather than dividing by zero`() {
        assertThat(MeasureUnit.fromLoggedEntry("slice", amount = 0.0, grams = 0.0))
            .isEqualTo(MeasureUnit.Grams)
    }

    @Test
    fun `known portion labels resolve back against the food's servings`() {
        val unit = MeasureUnit.fromLabel("slice", bread.servings)
        assertThat(unit).isEqualTo(MeasureUnit.Portion("slice", 38.0))
    }

    // ---- resolve and fromGrams must agree about a zero-gram portion ----

    @Test
    fun `a zero-gram portion is refused by resolve, as it always was by fromGrams`() {
        // Reachable: a custom food with a serving labelled "1 slice" and 0 g. NumericInput.isUsable
        // accepts zero, correctly -- a day can contain 0 g of a nutrient -- so nothing upstream
        // stops it. Multiplying unguarded would hand back Quantity(grams = 0.0), a
        // valid-looking answer, while fromGrams returns null for the same unit.
        val empty = MeasureUnit.Portion("slice", 0.0)

        assertThat(UnitConverter.resolve(1.0, empty, bread)).isNull()
        assertThat(UnitConverter.fromGrams(0.0, empty, bread)).isNull()
    }

    @Test
    fun `a negative-gram portion is refused too`() {
        val negative = MeasureUnit.Portion("slice", -38.0)
        assertThat(UnitConverter.resolve(1.0, negative, bread)).isNull()
        assertThat(UnitConverter.fromGrams(38.0, negative, bread)).isNull()
    }

    @Test
    fun `an ordinary portion still resolves both ways`() {
        // The guard must not cost the normal case, which is the whole of what this unit is for.
        val slice = MeasureUnit.Portion("slice", 38.0)
        assertThat(UnitConverter.resolve(2.0, slice, bread)?.grams).isEqualTo(76.0)
        assertThat(UnitConverter.fromGrams(76.0, slice, bread)).isEqualTo(2.0)
    }
}
