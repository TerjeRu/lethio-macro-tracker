package com.lethio.macros.ui.diary

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

class EntryCorrectionTest {
    private val entry =
        LogEntry(
            42,
            LocalDate.of(2026, 9, 1),
            MealType.BREAKFAST,
            FoodRef.Bundled(123),
            "Snapshot",
            quantity = Quantity(2.0, MeasureUnit.Portion("slice", 20.0), 40.0),
            macros = Macros(150.0, 5.0, 3.0, 27.0),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    @Test
    fun `amount edit rescales original snapshot and preserves identity and portion`() {
        val result = correctedEntry(entry, "3", MealType.LUNCH)!!
        assertThat(result.quantity.amount).isEqualTo(3.0)
        assertThat(result.quantity.grams).isEqualTo(60.0)
        assertThat(result.macros).isEqualTo(Macros(225.0, 7.5, 4.5, 40.5))
        assertThat(result.id).isEqualTo(42)
        assertThat(result.date).isEqualTo(entry.date)
        assertThat(result.foodName).isEqualTo(entry.foodName)
        assertThat(result.foodRef).isEqualTo(entry.foodRef)
        assertThat(result.createdAt).isEqualTo(entry.createdAt)
        assertThat(result.quantity.unit).isEqualTo(entry.quantity.unit)
        assertThat(result.meal).isEqualTo(MealType.LUNCH)
    }

    @Test
    fun `meal only retains exact nutrition and quantity`() {
        val result = correctedEntry(entry, "2", MealType.DINNER)!!
        assertThat(result.macros).isEqualTo(entry.macros)
        assertThat(result.quantity).isEqualTo(entry.quantity)
    }

    @Test
    fun `repeated corrections keep precision without display rounding`() {
        val third = correctedEntry(entry, "0.3333333333333333", MealType.BREAKFAST)!!
        val restored = correctedEntry(third, "2", MealType.BREAKFAST)!!
        assertThat(restored.macros.calories).isWithin(1e-10).of(150.0)
        assertThat(restored.macros.proteinG).isWithin(1e-10).of(5.0)
        assertThat(restored.macros.fatG).isWithin(1e-10).of(3.0)
        assertThat(restored.macros.carbsG).isWithin(1e-10).of(27.0)
        assertThat(restored.quantity.grams).isWithin(1e-10).of(40.0)
    }

    @Test
    fun `localized decimal accepted and invalid or overflowing edits refused`() {
        assertThat(correctedEntry(entry, "1,5", MealType.BREAKFAST)!!.macros.calories)
            .isEqualTo(112.5)
        listOf("", "NaN", "Infinity", "-1", "0", "1e309").forEach {
            assertThat(correctedEntry(entry, it, MealType.BREAKFAST)).isNull()
        }
    }

    @Test
    fun `zero original permits meal correction but no invented nutrition basis`() {
        val zero = entry.copy(quantity = entry.quantity.copy(amount = 0.0, grams = 0.0))
        assertThat(correctedEntry(zero, "0", MealType.LUNCH)!!.macros).isEqualTo(entry.macros)
        assertThat(correctedEntry(zero, "2", MealType.LUNCH)).isNull()
    }
}
