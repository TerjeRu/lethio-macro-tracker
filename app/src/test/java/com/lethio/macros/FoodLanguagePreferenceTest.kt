package com.lethio.macros

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The food-name language resolver: one stored string, one app language, one priority list.
 *
 * The default is the whole point of the test. A reader who never opens the picker must get exactly
 * what a single-language setting would give them; anything else changes behaviour for everyone in
 * order to help a few.
 */
class FoodLanguagePreferenceTest {

    @Test
    fun `an absent preference follows the app language and then English`() {
        assertThat(SettingsManager.foodLanguagesFor("", "nb")).containsExactly("nb", "en").inOrder()
        assertThat(SettingsManager.foodLanguagesFor("", "fr")).containsExactly("fr", "en").inOrder()
    }

    @Test
    fun `an English reader gets English once, not twice`() {
        // "en" + "en" would bind a duplicate into the IN clause and put a second copy in the
        // priority string, where INSTR would still find the first -- harmless but untrue.
        assertThat(SettingsManager.foodLanguagesFor("", "en")).containsExactly("en")
    }

    @Test
    fun `a stored list keeps its order and still ends in English`() {
        assertThat(SettingsManager.foodLanguagesFor("de,fr", "en"))
            .containsExactly("de", "fr", "en")
            .inOrder()
    }

    @Test
    fun `English chosen first stays first and does not lose the rest of the list`() {
        assertThat(SettingsManager.foodLanguagesFor("en,nb", "de"))
            .containsExactly("en", "nb")
            .inOrder()
    }

    @Test
    fun `whitespace and empty entries are ignored rather than bound as a language`() {
        assertThat(SettingsManager.foodLanguagesFor(" nb , , da ", "en"))
            .containsExactly("nb", "da", "en")
            .inOrder()
    }

    @Test
    fun `a stored list overrides the app language rather than being appended to it`() {
        // The expat case: English phone, Norwegian food names. If the app language were merged
        // in, "en" would sort above "nb" and the setting would do nothing.
        assertThat(SettingsManager.foodLanguagesFor("nb", "en"))
            .containsExactly("nb", "en")
            .inOrder()
    }

    @Test
    fun `the priority string delimits both ends so region tags cannot match inside each other`() {
        // Without the delimiters INSTR finds "en" inside "en-GB" at position 1, so a British
        // alias would rank as though it were the English one.
        val priority = SettingsManager.foodLanguagePriority(listOf("en-GB", "en"))
        assertThat(priority).isEqualTo(",en-GB,en,")
        assertThat(priority.indexOf(",en-GB,")).isLessThan(priority.indexOf(",en,"))
    }
}
