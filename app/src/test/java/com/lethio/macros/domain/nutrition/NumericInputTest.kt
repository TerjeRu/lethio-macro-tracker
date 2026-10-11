package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every one of these strings is something a person can put in a numeric field —
 * `KeyboardType.Number` discourages letters but does not stop a paste, and it does not stop a minus
 * sign on every IME.
 */
class NumericInputTest {

    @Test
    fun `an ordinary number is usable`() {
        assertThat(NumericInput.parse("250")).isEqualTo(NumericInput.Field.Number(250.0))
        assertThat(NumericInput.parse("0")).isEqualTo(NumericInput.Field.Number(0.0))
        assertThat(NumericInput.parse("12.5")).isEqualTo(NumericInput.Field.Number(12.5))
        assertThat(NumericInput.parse(" 40 ")).isEqualTo(NumericInput.Field.Number(40.0))
    }

    @Test
    fun `a negative number is not a measurement`() {
        // Otherwise -500 kcal is saved and subtracted from the day.
        assertThat(NumericInput.parse("-500")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("-0.1")).isEqualTo(NumericInput.Field.Unusable)
    }

    @Test
    fun `the non-finite values Double parses by name are refused`() {
        // String.toDoubleOrNull() accepts all of these on the JVM. NaN then propagates through
        // every total it touches, and Infinity is a perfectly valid database REAL.
        assertThat(NumericInput.parse("NaN")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("Infinity")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("-Infinity")).isEqualTo(NumericInput.Field.Unusable)
    }

    @Test
    fun `text that is not a number at all is refused`() {
        assertThat(NumericInput.parse("abc")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("-")).isEqualTo(NumericInput.Field.Unusable)
    }

    // ---- the decimal separator ----------------------------------------------------

    /**
     * `"1,5"` is how 1.5 is written in seven of the app's eight locales, and the app prints it that
     * way, so it must parse.
     */
    @Test
    fun `a comma is a decimal separator, because seven of eight locales write one`() {
        assertThat(NumericInput.parse("1,5")).isEqualTo(NumericInput.Field.Number(1.5))
        assertThat(NumericInput.parse("82,4")).isEqualTo(NumericInput.Field.Number(82.4))
        assertThat(NumericInput.parse("0,5")).isEqualTo(NumericInput.Field.Number(0.5))
        assertThat(NumericInput.parse(",5")).isEqualTo(NumericInput.Field.Number(0.5))
    }

    /**
     * Both, never one or the other. Keying off the locale's own symbol would newly reject "1.5" on
     * a German phone, and people carry a keyboard from one country and a phone from another.
     */
    @Test
    fun `a dot still parses everywhere, so a foreign keyboard is not newly broken`() {
        assertThat(NumericInput.parse("1.5")).isEqualTo(NumericInput.Field.Number(1.5))
        assertThat(NumericInput.parse("100")).isEqualTo(NumericInput.Field.Number(100.0))
    }

    /**
     * Ambiguous input is refused rather than guessed at. "1.234,5" and "1,234.5" are a European and
     * an English way of writing two different numbers and nothing here can tell which was meant.
     * These fields hold 0 to about 5,000, where grouping is never needed, so refusing costs nothing
     * real and guessing wrong costs a wrong meal.
     */
    @Test
    fun `more than one separator is refused rather than guessed at`() {
        assertThat(NumericInput.parse("1.234,5")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("1,234.5")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("1,2,3")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("1.2.3")).isEqualTo(NumericInput.Field.Unusable)
    }

    /**
     * A bare "1,234" is one separator, so it reads as 1.234 rather than as one thousand two hundred
     * and thirty-four. Stated as a test because it is the deliberate cost of the rule above, not an
     * oversight: thousands separators are not supported, and adding them because they are nearby is
     * how this becomes a guessing game.
     */
    @Test
    fun `a single separator is always decimal, never a thousands separator`() {
        assertThat(NumericInput.parse("1,234")).isEqualTo(NumericInput.Field.Number(1.234))
        assertThat(NumericInput.parse("1.234")).isEqualTo(NumericInput.Field.Number(1.234))
    }

    /** The guards the separator handling must not have quietly loosened. */
    @Test
    fun `normalising a separator does not admit a negative or a non-finite`() {
        assertThat(NumericInput.parse("-1,5")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("NaN,0")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse(",")).isEqualTo(NumericInput.Field.Unusable)
        assertThat(NumericInput.parse("1,")).isEqualTo(NumericInput.Field.Number(1.0))
    }

    @Test
    fun `blank is empty, not zero and not an error`() {
        // Quick Add reads Empty as "no protein entered", the goals editor as
        // "leave my current target alone". Flattening the two here would silently change one.
        assertThat(NumericInput.parse("")).isEqualTo(NumericInput.Field.Empty)
        assertThat(NumericInput.parse("   ")).isEqualTo(NumericInput.Field.Empty)
        assertThat(NumericInput.isUnusable("")).isFalse()
    }

    @Test
    fun `valueOr substitutes the caller's own meaning of empty`() {
        assertThat(NumericInput.valueOr("", 0.0)).isEqualTo(0.0)
        assertThat(NumericInput.valueOr("", 2000.0)).isEqualTo(2000.0)
        assertThat(NumericInput.valueOr("300", 2000.0)).isEqualTo(300.0)
        assertThat(NumericInput.valueOr("-300", 2000.0)).isNull()
    }

    @Test
    fun `isUsable is the same rule UnitConverter has always applied`() {
        assertThat(NumericInput.isUsable(0.0)).isTrue()
        assertThat(NumericInput.isUsable(100.0)).isTrue()
        assertThat(NumericInput.isUsable(-1.0)).isFalse()
        assertThat(NumericInput.isUsable(Double.NaN)).isFalse()
        assertThat(NumericInput.isUsable(Double.POSITIVE_INFINITY)).isFalse()
        assertThat(NumericInput.isUsable(Double.NEGATIVE_INFINITY)).isFalse()
    }

    @Test
    fun `implausible is about size, and never about validity`() {
        assertThat(NumericInput.isImplausible("6000", NumericInput.PLAUSIBLE_MAX_CALORIES)).isTrue()
        assertThat(NumericInput.isImplausible("4000", NumericInput.PLAUSIBLE_MAX_CALORIES))
            .isFalse()
        // An unusable value is not "implausible" -- it is refused outright, and saying both would
        // put two messages on screen for one mistake.
        assertThat(NumericInput.isImplausible("-6000", NumericInput.PLAUSIBLE_MAX_CALORIES))
            .isFalse()
        assertThat(NumericInput.isImplausible("", NumericInput.PLAUSIBLE_MAX_CALORIES)).isFalse()
    }
}
