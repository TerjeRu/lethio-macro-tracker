package com.lethio.macros

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The country question on [ChooseDatabaseScreen]: silent for almost everyone, and a real question
 * only when locale and SIM disagree on two countries the app actually has a table for.
 *
 * The default is the whole point of the test, same as [FoodLanguagePreferenceTest]: a reader whose
 * signals agree, or who only has one signal, must never see the screen.
 */
class OnboardingAskCountryTest {

    @Test
    fun `agreeing signals never ask`() {
        assertThat(SettingsManager.onboardingAskCountry("ES", "ES")).isNull()
    }

    @Test
    fun `no SIM never asks`() {
        assertThat(SettingsManager.onboardingAskCountry("US", null)).isNull()
        assertThat(SettingsManager.onboardingAskCountry("US", "")).isNull()
    }

    @Test
    fun `two different covered countries ask, pre-selecting the SIM country`() {
        // An expat's case: an English phone (locale resolves US/GB) on a Spanish SIM.
        assertThat(SettingsManager.onboardingAskCountry("US", "ES")).isEqualTo("ES")
    }

    @Test
    fun `disagreement is not enough on its own -- both sides must be covered`() {
        // Locale covered, SIM is a country with no national table: nothing to offer as an
        // alternative, so the existing no-bias default is already the right answer.
        assertThat(SettingsManager.onboardingAskCountry("US", "BR")).isNull()
        // SIM covered, locale is not.
        assertThat(SettingsManager.onboardingAskCountry("BR", "ES")).isNull()
        // Neither covered.
        assertThat(SettingsManager.onboardingAskCountry("BR", "IT")).isNull()
    }

    @Test
    fun `GB is asked about in the UK taxonomy vocabulary, not raw ISO`() {
        // Callers are expected to have already translated through ISO_TO_TAXONOMY_COUNTRY (see
        // countryFor and SettingsManager.simCountry), so "UK" -- never "GB" -- is what this
        // function compares against NATIONAL_TABLE_SOURCE.
        assertThat(SettingsManager.onboardingAskCountry("FR", "UK")).isEqualTo("UK")
    }

    @Test
    fun `the Netherlands and New Zealand count as covered`() {
        // Both have national tables, so a Dutch phone on a New Zealand SIM must ask rather than
        // be auto-accepted as two uncovered countries.
        assertThat(SettingsManager.onboardingAskCountry("NL", "NZ")).isEqualTo("NZ")
        assertThat(SettingsManager.onboardingAskCountry("UK", "NL")).isEqualTo("NL")
        assertThat(SettingsManager.onboardingAskCountry("NZ", "BR")).isNull()
    }
}
