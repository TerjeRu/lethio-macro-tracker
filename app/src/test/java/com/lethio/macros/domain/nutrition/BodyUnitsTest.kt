package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lethio.macros.domain.model.MassUnit
import org.junit.Test

/**
 * The round trip is the point of this file.
 *
 * A reader who types 12 st 4 lb, closes the sheet and reopens it must see 12 st 4 lb. That is a
 * property of display -> kg -> display taken together, so it is swept across the whole plausible
 * range rather than asserted at a handful of chosen points: the failure mode is a value that drifts
 * on one input in a hundred and looks perfectly fine on the other ninety-nine.
 */
class BodyUnitsTest {

    // ---- factors --------------------------------------------------------------------------

    @Test
    fun `pound and inch factors are the exact 1959 definitions`() {
        assertThat(BodyUnits.KG_PER_POUND).isEqualTo(0.45359237)
        assertThat(BodyUnits.CM_PER_INCH).isEqualTo(2.54)
        assertThat(BodyUnits.POUNDS_PER_STONE).isEqualTo(14)
    }

    @Test
    fun `known conversions match published values`() {
        // 1 kg is 2.2046 lb to four places; 100 kg is 15 st 10.5 lb.
        assertThat(BodyUnits.kgToPounds(1.0)).isWithin(0.0001).of(2.2046)
        val hundred = BodyUnits.kgToStonePounds(100.0)
        assertThat(hundred.stone).isEqualTo(15)
        assertThat(hundred.pounds).isWithin(0.05).of(10.5)

        // 180 cm is 5 ft 10.9 in.
        val tall = BodyUnits.cmToFeetInches(180.0)
        assertThat(tall.feet).isEqualTo(5)
        assertThat(tall.inches).isWithin(0.05).of(10.9)
    }

    // ---- the rollovers --------------------------------------------------------------------

    /**
     * The case a naive implementation gets wrong: rounding the pounds up to a full 14 produces a
     * reading that does not exist. Carrying has to happen after the rounding, not before.
     */
    @Test
    fun `pounds never display as 14`() {
        var kg = 10.0
        while (kg <= 300.0) {
            val sp = BodyUnits.kgToStonePounds(kg)
            assertThat(sp.pounds).isAtLeast(0.0)
            assertThat(sp.pounds).isLessThan(14.0)
            kg += 0.001
        }
    }

    @Test
    fun `inches never display as 12`() {
        var cm = 50.0
        while (cm <= 250.0) {
            val fi = BodyUnits.cmToFeetInches(cm)
            assertThat(fi.inches).isAtLeast(0.0)
            assertThat(fi.inches).isLessThan(12.0)
            cm += 0.001
        }
    }

    /** The exact boundary, stated as a value rather than found by sweeping. */
    @Test
    fun `just under a whole stone carries rather than reading 14 pounds`() {
        // 13.9999 st in kg. Rounding the remainder alone would give "13 st 14.0 lb".
        val kg = BodyUnits.stonePoundsToKg(13, 13.9999)
        val sp = BodyUnits.kgToStonePounds(kg)
        assertThat(sp.stone).isEqualTo(14)
        assertThat(sp.pounds).isEqualTo(0.0)
    }

    @Test
    fun `just under a whole foot carries rather than reading 12 inches`() {
        val cm = BodyUnits.feetInchesToCm(5, 11.9999)
        val fi = BodyUnits.cmToFeetInches(cm)
        assertThat(fi.feet).isEqualTo(6)
        assertThat(fi.inches).isEqualTo(0.0)
    }

    // ---- round trips ------------------------------------------------------------------------

    /**
     * Every stone-and-pounds reading a person could plausibly hold, taken to kilograms and back.
     *
     * 2 st to 47 st at every tenth of a pound is 31,360 readings, which covers the whole range
     * `GoalCalculator` will accept (10 kg to 650 kg) and then some.
     */
    @Test
    fun `stone and pounds survives a trip through kilograms`() {
        for (stone in 2..47) {
            for (tenths in 0 until 140) {
                val pounds = tenths / 10.0
                val kg = BodyUnits.stonePoundsToKg(stone, pounds)
                val back = BodyUnits.kgToStonePounds(kg)
                assertWithMessage(
                        "$stone st $pounds lb -> $kg kg -> ${back.stone} st ${back.pounds} lb"
                    )
                    .that(back.stone to back.pounds)
                    .isEqualTo(stone to pounds)
            }
        }
    }

    @Test
    fun `feet and inches survives a trip through centimetres`() {
        for (feet in 1..8) {
            for (tenths in 0 until 120) {
                val inches = tenths / 10.0
                val cm = BodyUnits.feetInchesToCm(feet, inches)
                val back = BodyUnits.cmToFeetInches(cm)
                assertWithMessage(
                        "$feet ft $inches in -> $cm cm -> ${back.feet} ft ${back.inches} in"
                    )
                    .that(back.feet to back.inches)
                    .isEqualTo(feet to inches)
            }
        }
    }

    @Test
    fun `pounds survive a trip through kilograms`() {
        for (tenths in 220..14000) {
            val pounds = tenths / 10.0
            val back = BodyUnits.kgToPounds(BodyUnits.poundsToKg(pounds))
            assertThat(BodyUnits.formatField(back)).isEqualTo(BodyUnits.formatField(pounds))
        }
    }

    @Test
    fun `rate survives a trip through kilograms in every mode`() {
        for (unit in MassUnit.entries) {
            for (hundredths in 1..300) {
                val displayed = hundredths / 100.0
                val back = BodyUnits.rateToDisplay(BodyUnits.rateToKg(displayed, unit), unit)
                assertThat(back).isWithin(1e-9).of(displayed)
            }
        }
    }

    // ---- the rate is pounds under both imperial modes ---------------------------------------

    @Test
    fun `rate is pounds under both imperial modes and kilograms under metric`() {
        assertThat(BodyUnits.rateToDisplay(0.5, MassUnit.METRIC)).isEqualTo(0.5)
        // Half a kilogram a week is almost exactly a pound, which is why the default reads as 1.
        assertThat(BodyUnits.rateToDisplay(0.5, MassUnit.IMPERIAL_US)).isWithin(0.005).of(1.1023)
        assertThat(BodyUnits.rateToDisplay(0.5, MassUnit.IMPERIAL_UK))
            .isEqualTo(BodyUnits.rateToDisplay(0.5, MassUnit.IMPERIAL_US))
    }

    // ---- formatting -------------------------------------------------------------------------

    @Test
    fun `whole numbers lose their trailing zero and fractions keep one place`() {
        assertThat(BodyUnits.formatField(82.0)).isEqualTo("82")
        assertThat(BodyUnits.formatField(82.04)).isEqualTo("82")
        assertThat(BodyUnits.formatField(82.45)).isEqualTo("82.5")
        assertThat(BodyUnits.formatField(0.0)).isEqualTo("0")
    }

    // ---- the enum ---------------------------------------------------------------------------

    @Test
    fun `the retired imperial value maps to pounds rather than silently reverting to metric`() {
        assertThat(MassUnit.fromValue("imperial")).isEqualTo(MassUnit.IMPERIAL_US)
        assertThat(MassUnit.fromValue("imperial_uk")).isEqualTo(MassUnit.IMPERIAL_UK)
        assertThat(MassUnit.fromValue(null)).isEqualTo(MassUnit.METRIC)
        assertThat(MassUnit.fromValue("nonsense")).isEqualTo(MassUnit.METRIC)
    }

    @Test
    fun `only the UK uses stone and both imperial modes use feet and inches`() {
        assertThat(MassUnit.IMPERIAL_UK.usesStone).isTrue()
        assertThat(MassUnit.IMPERIAL_US.usesStone).isFalse()
        assertThat(MassUnit.METRIC.usesStone).isFalse()
        assertThat(MassUnit.IMPERIAL_US.usesFeetInches).isTrue()
        assertThat(MassUnit.IMPERIAL_UK.usesFeetInches).isTrue()
        assertThat(MassUnit.METRIC.usesFeetInches).isFalse()
    }
}
