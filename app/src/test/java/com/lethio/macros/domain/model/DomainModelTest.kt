package com.lethio.macros.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the specific defects the previous domain model shipped with. Each test here corresponds to
 * a real bug, not a hypothetical.
 */
class DomainModelTest {

    @Test
    fun `MealType fromValue never throws on unknown input`() {
        // The old implementation used entries.first { }, which raised NoSuchElementException.
        // It ran for every row rendered on the home screen, so one bad row killed the main screen.
        assertThat(MealType.fromValue(99)).isEqualTo(MealType.SNACK)
        assertThat(MealType.fromValue(-1)).isEqualTo(MealType.SNACK)
        assertThat(MealType.fromValue(0)).isEqualTo(MealType.SNACK)
    }

    @Test
    fun `no MealType uses zero, so a defaulted column cannot silently mean snack`() {
        assertThat(MealType.entries.map { it.value }).doesNotContain(0)
    }

    @Test
    fun `MealType values are unique`() {
        val values = MealType.entries.map { it.value }
        assertThat(values).containsNoDuplicates()
    }

    @Test
    fun `FoodRef Kind fromValue never throws on unknown input`() {
        assertThat(FoodRef.Kind.fromValue("nonsense")).isEqualTo(FoodRef.Kind.QUICK_ADD)
        assertThat(FoodRef.Kind.fromValue("")).isEqualTo(FoodRef.Kind.QUICK_ADD)
    }

    @Test
    fun `legacy db value is not silently treated as bundled`() {
        // 'db' was the pre-migration value; MIGRATION_2_3 rewrites it. If one survives, it must
        // land somewhere harmless rather than mis-resolving to a bundled id.
        assertThat(FoodRef.Kind.fromValue("db")).isEqualTo(FoodRef.Kind.QUICK_ADD)
    }

    @Test
    fun `QuickAdd has no id by construction`() {
        assertThat(FoodRef.QuickAdd.id).isNull()
        assertThat(FoodRef.QuickAdd.kind).isEqualTo(FoodRef.Kind.QUICK_ADD)
    }

    @Test
    fun `a reference kind with a null id degrades to QuickAdd rather than a broken lookup`() {
        // This is the state that caused "DELETE ... WHERE food_id = :id" never to match,
        // making quick-add favourites impossible to remove.
        assertThat(FoodRef.of(FoodRef.Kind.CUSTOM, null)).isEqualTo(FoodRef.QuickAdd)
        assertThat(FoodRef.of(FoodRef.Kind.BUNDLED, null)).isEqualTo(FoodRef.QuickAdd)
    }

    @Test
    fun `FoodRef round trips through its persisted pair`() {
        listOf(FoodRef.Bundled(42), FoodRef.Custom(7), FoodRef.QuickAdd).forEach { ref ->
            assertThat(FoodRef.of(ref.kind, ref.id)).isEqualTo(ref)
        }
    }

    @Test
    fun `reference kind and dataset provenance are separate vocabularies`() {
        // Two concepts, two vocabularies: sharing one column and one enum made them collide.
        val kinds = FoodRef.Kind.entries.map { it.value }.toSet()
        val sources = DataSource.entries.map { it.value }.toSet()
        assertThat(kinds.intersect(sources)).isEmpty()
    }

    @Test
    fun `Macros scales a per-100g composition to a portion`() {
        val per100g = Macros(calories = 247.0, proteinG = 13.0, fatG = 3.4, carbsG = 41.0)
        val forSlice = per100g.forGrams(38.0)
        assertThat(forSlice.calories).isWithin(0.001).of(247.0 * 0.38)
        assertThat(forSlice.proteinG).isWithin(0.001).of(13.0 * 0.38)
    }

    @Test
    fun `Macros sum componentwise`() {
        val a = Macros(100.0, 10.0, 5.0, 2.0)
        val b = Macros(50.0, 1.0, 2.0, 8.0)
        assertThat(a + b).isEqualTo(Macros(150.0, 11.0, 7.0, 10.0))
    }

    @Test
    fun `adding zero changes nothing`() {
        val a = Macros(100.0, 10.0, 5.0, 2.0)
        assertThat(a + Macros.ZERO).isEqualTo(a)
    }

    @Test
    fun `a food only offers volume units when its density is known`() {
        val noDensity = Food(ref = FoodRef.Bundled(1), name = "Bread", per100g = Macros())
        assertThat(noDensity.availableUnits).doesNotContain(MeasureUnit.Milliliters)

        val withDensity = noDensity.copy(densityGPerMl = 0.918)
        assertThat(withDensity.availableUnits).contains(MeasureUnit.Milliliters)
    }

    @Test
    fun `a serving named after a built-in unit does not produce a duplicate chip`() {
        // USDA's portion table contains entries literally named "oz". Left alone, the unit picker
        // showed two identical "oz" chips, both highlighted, and defaulted a croissant to "1 oz".
        val food =
            Food(
                ref = FoodRef.Bundled(1),
                name = "Croissants, butter",
                per100g = Macros(calories = 406.0),
                servings = listOf(Serving("oz", 28.35), Serving("croissant, medium", 57.0)),
            )
        val labels = food.availableUnits.map { it.label.lowercase() }
        assertThat(labels).containsNoDuplicates()
        assertThat(labels).contains("croissant, medium")
    }

    @Test
    fun `default serving falls back to the first when none is marked`() {
        val food =
            Food(
                ref = FoodRef.Bundled(1),
                name = "Bread",
                per100g = Macros(),
                servings = listOf(Serving("slice", 38.0), Serving("loaf", 800.0)),
            )
        assertThat(food.defaultServing).isEqualTo(Serving("slice", 38.0))
    }
}
