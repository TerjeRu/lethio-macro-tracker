package com.lethio.macros.ui.product

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules deciding what may be saved, and the stricter ones deciding what may be published.
 *
 * The gap between [AddProductUiState.canSave] and [AddProductUiState.canContribute] is the point of
 * this screen: a product can be worth keeping in your own diary while still not being worth
 * asserting to a database other people read back as fact.
 */
class AddProductUiStateTest {

    private fun state(
        barcode: String = "3017620422003",
        name: String = "Test Product",
        calories: String = "539",
        protein: String = "6.3",
        fat: String = "30.9",
        carbs: String = "57.5",
        offConfirmedMissing: Boolean = true,
    ) =
        AddProductUiState(
            barcode = barcode,
            name = name,
            calories = calories,
            protein = protein,
            fat = fat,
            carbs = carbs,
            offConfirmedMissing = offConfirmedMissing,
        )

    @Test
    fun `a complete product can be saved and contributed`() {
        val s = state()

        assertThat(s.canSave).isTrue()
        assertThat(s.canContribute).isTrue()
    }

    @Test
    fun `nothing is contributed for a barcode Open Food Facts was never asked about`() {
        // The rule that stops the app submitting duplicates of products OFF already has under a
        // name this app simply failed to match.
        val s = state(offConfirmedMissing = false)

        assertThat(s.canSave).isTrue()
        assertThat(s.canContribute).isFalse()
    }

    @Test
    fun `a product with no barcode is saved locally but never contributed`() {
        val s = state(barcode = "")

        assertThat(s.canSave).isTrue()
        assertThat(s.canContribute).isFalse()
    }

    @Test
    fun `a barcode that is not a barcode is never contributed`() {
        assertThat(state(barcode = "12345").canContribute).isFalse()
        assertThat(state(barcode = "not-digits-here").canContribute).isFalse()
    }

    @Test
    fun `values that fail the app's own quality gate are never contributed`() {
        // Kilojoules entered as kilocalories -- the single most common corruption in this dataset,
        // and exactly what AtwaterCheck exists to catch. Savable (the reader may know better than
        // the app), never publishable.
        val kjAsKcal = state(calories = "2252")

        assertThat(kjAsKcal.canSave).isTrue()
        assertThat(kjAsKcal.canContribute).isFalse()
    }

    @Test
    fun `relay-ineligible text remains locally savable`() {
        for (name in listOf("safe\u00adlooking", "safe\u200dlooking", "safe\u202elooking")) {
            val product = state(name = name)
            assertThat(product.canSave).isTrue()
            assertThat(product.canContribute).isFalse()
        }
        val invalidBrand = state().copy(brand = "safe\u202elooking")
        assertThat(invalidBrand.canSave).isTrue()
        assertThat(invalidBrand.canContribute).isFalse()
    }

    @Test
    fun `macros summing past a hundred grams are never contributed`() {
        val impossible = state(protein = "60", fat = "60", carbs = "60")

        assertThat(impossible.canContribute).isFalse()
    }

    @Test
    fun `a nameless or numberless product cannot even be saved`() {
        // Stricter than Quick Add on purpose: this defines a reusable product, not one meal.
        assertThat(state(name = "  ").canSave).isFalse()
        assertThat(state(calories = "").canSave).isFalse()
    }

    @Test
    fun `a negative value blocks saving and marks its own field`() {
        val s = state(protein = "-5")

        assertThat(s.canSave).isFalse()
        assertThat(s.invalidFields).contains(ProductField.PROTEIN)
        assertThat(s.invalidFields).doesNotContain(ProductField.FAT)
    }

    @Test
    fun `a comma decimal is read as a decimal, not refused`() {
        // Seven of the app's eight locales use a comma.
        val s = state(protein = "6,3")

        assertThat(s.canSave).isTrue()
        assertThat(s.macros.proteinG).isWithin(0.001).of(6.3)
    }

    @Test
    fun `the energy warning is advisory and does not block saving`() {
        val s = state(calories = "900", protein = "0", fat = "0", carbs = "0")

        assertThat(s.energyLooksInconsistent).isFalse() // no macros entered, nothing to compare
        assertThat(state(calories = "900", protein = "1").energyLooksInconsistent).isTrue()
        assertThat(state(calories = "900", protein = "1").canSave).isTrue()
    }

    @Test
    fun `zero energy can be saved locally but remains outside relay v1`() {
        val water =
            state(name = "Mineral water", calories = "0", protein = "0", fat = "0", carbs = "0")
        assertThat(water.canSave).isTrue()
        assertThat(water.canContribute).isFalse()
    }
}
