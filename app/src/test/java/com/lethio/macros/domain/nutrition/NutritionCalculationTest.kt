package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.*
import org.junit.Assert.assertThrows
import org.junit.Test

class NutritionCalculationTest {
    @Test
    fun publisherOriginAndFortificationSurviveScalingAndSnapshotCodec() {
        // Shape written by the stdlib source reader, including distinct agreeing references.
        val raw =
            """{"nutrients":{"FIBRE":{"value":1.2,"sourceValue":"1,2","sourceUnit":"g","sourceValueType":"+","sourceOrigin":{"dataset":"nevo","version":"NEVO-Online 2025 9.0","foodId":"42","nutrientCode":"FIBT","references":[{"code":"A","reference":"First reference"},{"code":"B","reference":"Different reference"}]}}}}"""
        val composition = requireNotNull(NutritionCodec.decode(raw)[Nutrient.FIBRE])
        val snapshot = composition * 2.5
        val restored =
            requireNotNull(
                NutritionCodec.decode(NutritionCodec.encode(mapOf(Nutrient.FIBRE to snapshot)))[
                        Nutrient.FIBRE]
            )
        assertThat(restored.value).isEqualTo(3.0)
        assertThat(restored.sourceValue).isEqualTo("1,2")
        assertThat(restored.sourceValueType).isEqualTo("+")
        assertThat(restored.sourceOrigin).isEqualTo(composition.sourceOrigin)
        assertThat(restored.sourceOrigin?.references).hasSize(2)
        assertThat(restored.isQualified).isFalse()
        assertThat((restored + NutrientValue(1.0)).sourceOrigin).isNull()
    }

    @Test
    fun optionalNutrientTotalsHaveNoPhantomMissingFood() {
        val a = Macros(40.0).withNutrients(mapOf(Nutrient.SODIUM to NutrientValue(0.4)))
        val b = Macros(60.0).withNutrients(mapOf(Nutrient.SODIUM to NutrientValue(0.2)))
        assertThat(Macros.sum(listOf(a)).isComplete(Nutrient.SODIUM)).isTrue()
        val sum = Macros.sum(listOf(a, b))
        assertThat(sum.nutrient(Nutrient.SODIUM).value).isWithin(1e-12).of(0.6)
        assertThat(sum.isComplete(Nutrient.SODIUM)).isTrue()
        assertThat(Macros.sum(listOf(a, Macros(10.0))).isComplete(Nutrient.SODIUM)).isFalse()
        assertThat(Macros.sum(emptyList())).isEqualTo(Macros.ZERO)
    }

    @Test
    fun reportedEstimatesAndAveragesRemainNumericWithTheirOriginalValueTypes() {
        for ((code, kind) in
            listOf(
                "BE" to NutrientQualifier.BEST_ESTIMATE,
                "AV" to NutrientQualifier.AVERAGE,
                "W" to NutrientQualifier.WEIGHTED,
                "X" to NutrientQualifier.UNSPECIFIED,
            )) {
            val source = NutrientValue(2.5, setOf(kind), sourceValueType = code)
            val snapshot = source * 2.5
            val restored =
                NutritionCodec.decode(NutritionCodec.encode(mapOf(Nutrient.PROTEIN to snapshot)))
            assertThat(restored[Nutrient.PROTEIN]?.reportedValue).isEqualTo(6.25)
            assertThat(restored[Nutrient.PROTEIN]?.sourceValueType).isEqualTo(code)
            assertThat((snapshot + NutrientValue(1.0)).isIncomplete).isFalse()
            assertThat((snapshot + NutrientValue(1.0)).qualifiers).contains(kind)
        }
    }

    private fun drink(density: Double? = null) =
        Food(
            FoodRef.Bundled(999),
            "Synthetic drink",
            per100g = Macros(40.0, 2.0, 1.0, 3.0),
            densityGPerMl = density,
            nutritionBasis = NutritionBasis.VOLUME,
        )

    @Test
    fun nativeMlDoesNotRequireMass() {
        for ((amount, kcal) in listOf(50.0 to 20.0, 100.0 to 40.0, 250.0 to 100.0)) {
            val food = drink()
            val q = requireNotNull(UnitConverter.resolve(amount, MeasureUnit.Milliliters, food))
            assertThat(q.grams).isNull()
            assertThat(q.basis).isEqualTo(NutritionBasis.VOLUME)
            assertThat(q.basisAmount).isEqualTo(amount)
            assertThat(food.nutritionFor(q)?.calories).isEqualTo(kcal)
        }
    }

    @Test
    fun nativeVolumeOffersOnlySupportedUnits() {
        assertThat(drink().availableUnits).containsExactly(MeasureUnit.Milliliters)
        assertThat(UnitConverter.resolve(250.0, MeasureUnit.Grams, drink())).isNull()
        assertThat(UnitConverter.resolve(1.0, MeasureUnit.Ounces, drink())).isNull()
        assertThat(UnitConverter.resolve(1.0, MeasureUnit.Portion("cup", 200.0), drink())).isNull()
    }

    @Test
    fun sourceDensityPermitsMassAndNamedPortions() {
        val food = drink(0.8)
        val q = requireNotNull(UnitConverter.resolve(200.0, MeasureUnit.Grams, food))
        assertThat(q.grams).isEqualTo(200.0)
        assertThat(q.basisAmount).isEqualTo(250.0)
        assertThat(food.nutritionFor(q)?.calories).isEqualTo(100.0)
        val portion =
            requireNotNull(UnitConverter.resolve(2.0, MeasureUnit.Portion("cup", 100.0), food))
        assertThat(food.nutritionFor(portion)?.calories).isEqualTo(100.0)
        val oz = requireNotNull(UnitConverter.resolve(1.0, MeasureUnit.Ounces, food))
        assertThat(food.nutritionFor(oz)?.calories).isWithin(1e-9).of(14.1747615625)
    }

    @Test
    fun massBasisStillUsesMassWhenEnteredInMl() {
        val food = drink(0.8).copy(nutritionBasis = NutritionBasis.MASS)
        val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, food))
        assertThat(q.basisAmount).isEqualTo(200.0)
        assertThat(food.nutritionFor(q)?.calories).isEqualTo(80.0)
        assertThat(food.nutritionFor(q.copy(basis = NutritionBasis.VOLUME))).isNull()
    }

    @Test
    fun unusableAmountsAndDensitiesCannotInventConversions() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(UnitConverter.resolve(value, MeasureUnit.Milliliters, drink())).isNull()
        }
        for (density in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(drink(density).availableUnits).containsExactly(MeasureUnit.Milliliters)
            assertThat(UnitConverter.resolve(100.0, MeasureUnit.Grams, drink(density))).isNull()
        }
        val zero = requireNotNull(UnitConverter.resolve(0.0, MeasureUnit.Milliliters, drink()))
        assertThat(drink().nutritionFor(zero)?.calories).isEqualTo(0.0)
        assertThat(UnitConverter.resolve(Double.MAX_VALUE, MeasureUnit.Ounces, drink(0.8))).isNull()
    }

    @Test
    fun belowDetectionZeroRemainsNumericQualifiedWithoutInventedBound() {
        val fat =
            NutrientValue(
                0.0,
                setOf(NutrientQualifier.BELOW_DETECTION),
                sourceValue = "0",
                sourceUnit = "g",
            )
        val source = drink().copy(per100g = Macros(40.0).withNutrients(mapOf(Nutrient.FAT to fat)))
        val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, source))
        val total = requireNotNull(source.nutritionFor(q)) + Macros(fatG = 10.0)
        assertThat(total.fatG).isEqualTo(10.0)
        assertThat(total.nutrient(Nutrient.FAT).qualifiers)
            .contains(NutrientQualifier.BELOW_DETECTION)
        assertThat(total.nutrient(Nutrient.FAT).upperBound).isNull()
        assertThat(total.isComplete(Nutrient.FAT)).isTrue()
    }

    @Test
    fun missingAndUndecidableKeepKnownSubtotalAndOnlyAffectedNutrientIncomplete() {
        for (missing in
            listOf(NutrientValue(), NutrientValue(0.0, setOf(NutrientQualifier.UNDECIDABLE)))) {
            val source = Macros(40.0).withNutrients(mapOf(Nutrient.FAT to missing))
            val total = Macros.ZERO + source + Macros(60.0, fatG = 12.0)
            assertThat(total.calories).isEqualTo(100.0)
            assertThat(total.fatG).isEqualTo(12.0)
            assertThat(total.nutrient(Nutrient.FAT).reportedValue).isEqualTo(12.0)
            assertThat(total.isComplete(Nutrient.FAT)).isFalse()
            assertThat(total.isComplete(Nutrient.CALORIES)).isTrue()
        }
    }

    @Test
    fun sodiumUnitAndExplicitBoundSurviveScalingStorageAndSums() {
        // Canonical 0.4 g is the independent conversion of the source's 400 mg.
        val sodium =
            NutrientValue(
                0.4,
                setOf(NutrientQualifier.BOUNDED),
                upperBound = 0.4,
                sourceValue = "<400",
                sourceUnit = "mg",
            )
        val macros = Macros(40.0).withNutrients(mapOf(Nutrient.SODIUM to sodium)) * 2.5
        val restored = NutritionCodec.decode(NutritionCodec.encode(macros.nutrients))
        assertThat(restored[Nutrient.SODIUM]?.value).isEqualTo(1.0)
        assertThat(restored[Nutrient.SODIUM]?.upperBound).isEqualTo(1.0)
        assertThat(restored[Nutrient.SODIUM]?.sourceValue).isEqualTo("<400")
        assertThat(restored[Nutrient.SODIUM]?.sourceUnit).isEqualTo("mg")
        val sum = requireNotNull(restored[Nutrient.SODIUM]) + NutrientValue(0.2)
        assertThat(sum.value).isEqualTo(1.2)
        assertThat(sum.upperBound).isEqualTo(1.2)
        assertThat((sum + NutrientValue()).upperBound).isNull()
    }

    @Test
    fun codecDistinguishesZeroMissingTraceLogicalZeroAndUnknownOptional() {
        val values =
            mapOf(
                Nutrient.FAT to NutrientValue(0.0, setOf(NutrientQualifier.TRACE)),
                Nutrient.PROTEIN to NutrientValue(0.0, setOf(NutrientQualifier.LOGICAL_ZERO)),
                Nutrient.FIBRE to NutrientValue(),
                Nutrient.SODIUM to NutrientValue(0.0),
            )
        assertThat(NutritionCodec.decode(NutritionCodec.encode(values))).isEqualTo(values)
        assertThat(NutritionCodec.decode(null)).isEmpty()
        assertThat(NutritionCodec.encode(emptyMap())).isNull()
        assertThat(values[Nutrient.FAT]?.isQualified).isTrue()
        assertThat(values[Nutrient.PROTEIN]?.isQualified).isFalse()
        assertThat(values[Nutrient.FIBRE]?.reportedValue).isNull()
    }

    @Test
    fun malformedOrNonfiniteMetadataFailsExplicitly() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { NutrientValue(value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NutrientValue(1.0, setOf(NutrientQualifier.LOGICAL_ZERO))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NutrientValue(lowerBound = 3.0, upperBound = 2.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NutritionCodec.decode("{\"version\":2,\"nutrients\":{}}")
        }
        assertThrows(IllegalArgumentException::class.java) { NutritionBasis.fromCode("kg") }
    }

    @Test
    fun oldMassValuesAndPublisherCaloriesAreUnchanged() {
        val food = drink().copy(nutritionBasis = NutritionBasis.MASS)
        val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Grams, food))
        assertThat(food.nutritionFor(q)).isEqualTo(Macros(100.0, 5.0, 2.5, 7.5))
        // 4/4/9 would produce 72.5; the source kcal is retained.
        assertThat(Macros(0.4, 0.04, 0.03, 0.01).forGrams(33.0).calories).isWithin(1e-12).of(0.132)
    }

    @Test
    fun actualPythonParserContractDecodesAndScalesInTheAndroidModel() {
        val raw =
            requireNotNull(javaClass.classLoader?.getResource("nutrition-contract.json")).readText()
        val values = NutritionCodec.decode(raw)
        val source = drink().copy(per100g = Macros().withNutrients(values))
        val amount = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, source))
        val portion = requireNotNull(source.nutritionFor(amount))
        assertThat(portion.calories).isEqualTo(100.0)
        assertThat(portion.proteinG).isEqualTo(6.25)
        assertThat(portion.nutrient(Nutrient.PROTEIN).sourceValueType).isEqualTo("BE")
        assertThat(portion.fatG).isEqualTo(0.0)
        assertThat(portion.nutrient(Nutrient.FAT).qualifiers)
            .contains(NutrientQualifier.BELOW_DETECTION)
        assertThat(portion.nutrient(Nutrient.SODIUM).upperBound).isEqualTo(1.0)
        assertThat(portion.nutrient(Nutrient.SODIUM).upperInclusive).isFalse()
        assertThat(portion.nutrient(Nutrient.FIBRE).reportedValue).isNull()
        assertThat(portion.nutrient(Nutrient.SODIUM).sourceUnit).isEqualTo("mg")
    }
}
