package com.lethio.macros.ui.util

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.domain.model.NutrientQualifier
import com.lethio.macros.domain.model.NutrientValue
import org.junit.Test

/** Which single-food figures read "trace" instead of "0 g". */
class TraceLabelsTest {

    private fun fat(value: Double?, vararg qualifiers: NutrientQualifier) =
        Macros(fatG = value ?: 0.0)
            .withNutrients(
                mapOf(Nutrient.FAT to NutrientValue(value = value, qualifiers = qualifiers.toSet()))
            )

    @Test
    fun `trace and below-detection zeros read as trace`() {
        // NEVO stores trace fat as 0 (e.g. canned tomato puree); FOODfiles BL fat likewise.
        assertThat(fat(0.0, NutrientQualifier.TRACE).isTrace(Nutrient.FAT)).isTrue()
        assertThat(fat(0.0, NutrientQualifier.BELOW_DETECTION).isTrace(Nutrient.FAT)).isTrue()
        assertThat(fat(0.0, NutrientQualifier.BELOW_QUANTIFICATION).isTrace(Nutrient.FAT)).isTrue()
    }

    @Test
    fun `a published zero, an estimate or a real number does not`() {
        assertThat(fat(0.0, NutrientQualifier.LOGICAL_ZERO).isTrace(Nutrient.FAT)).isFalse()
        assertThat(fat(0.0).isTrace(Nutrient.FAT)).isFalse()
        assertThat(fat(3.1, NutrientQualifier.BEST_ESTIMATE).isTrace(Nutrient.FAT)).isFalse()
        assertThat(fat(0.4, NutrientQualifier.TRACE).isTrace(Nutrient.FAT)).isFalse()
        assertThat(Macros(fatG = 0.0).isTrace(Nutrient.FAT)).isFalse()
    }

    @Test
    fun `a logged portion of a trace figure is still trace`() {
        val portion =
            Macros()
                .withNutrients(
                    mapOf(
                        Nutrient.FAT to
                            NutrientValue(
                                value = 0.0,
                                qualifiers = setOf(NutrientQualifier.TRACE),
                            ) * 2.5
                    )
                )
        assertThat(portion.isTrace(Nutrient.FAT)).isTrue()
    }
}
