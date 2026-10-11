package com.lethio.macros.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Credits come from the saved snapshots being shown, never from the catalogue. */
class CalculationCreditTest {

    private fun origin(dataset: String) = NutrientOrigin(dataset, "1", "A1", "PROT", emptyList())

    private fun saved(dataset: String?) =
        Macros(proteinG = 3.0)
            .withNutrients(
                dataset?.let {
                    mapOf(Nutrient.PROTEIN to NutrientValue(value = 3.0, sourceOrigin = origin(it)))
                } ?: emptyMap()
            )

    @Test
    fun `a day with a NEVO entry owes exactly the approved RIVM credit`() {
        assertThat(calculationCreditsFor(listOf(saved("nevo"), saved(null), saved("usda"))))
            .containsExactly(
                "Based on data from NEVO online version 2025/9.0, RIVM, Bilthoven and other data sources"
            )
    }

    @Test
    fun `a day without NEVO owes no calculation credit`() {
        assertThat(calculationCreditsFor(listOf(saved("foodfiles"), saved("usda"), saved(null))))
            .isEmpty()
        assertThat(calculationCreditsFor(emptyList())).isEmpty()
    }

    @Test
    fun `a summed day keeps the credit even though aggregates drop origins`() {
        // Macros.plus drops individual origins, so the credit must be derived from
        // the entries, not from the day's total.
        val total = saved("nevo") + saved("usda")
        assertThat(datasetsIn(listOf(total))).isEmpty()
        assertThat(datasetsIn(listOf(saved("nevo"), saved("usda"))))
            .containsExactly(DataSource.NEVO, DataSource.USDA)
    }

    @Test
    fun `an unknown dataset name is ignored rather than crashing the diary`() {
        assertThat(datasetsIn(listOf(saved("not-a-source")))).isEmpty()
    }
}
