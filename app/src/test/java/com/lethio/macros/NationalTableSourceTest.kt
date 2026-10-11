package com.lethio.macros

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The country-to-table wiring for NEVO and FOODfiles and the lead-word gate. */
class NationalTableSourceTest {

    @Test
    fun `NL and NZ select their own national tables`() {
        assertThat(SettingsManager.nationalSourceFor("NL")).isEqualTo("nevo")
        assertThat(SettingsManager.nationalSourceFor("NZ")).isEqualTo("foodfiles")
    }

    @Test
    fun `the lead-word rule is limited to the two new tables`() {
        // Applied to the established tables it reordered 8 of 337 existing-market lists, so
        // widening this set is a ranking change that needs its own measurement.
        assertThat(SettingsManager.LEAD_WORD_SOURCES).containsExactly("nevo", "foodfiles")
        for (source in SettingsManager.LEAD_WORD_SOURCES) {
            assertThat(listOf("NL", "NZ").map(SettingsManager::nationalSourceFor)).contains(source)
        }
        assertThat(SettingsManager.nationalSourceFor("US"))
            .isNotIn(SettingsManager.LEAD_WORD_SOURCES)
    }
}
