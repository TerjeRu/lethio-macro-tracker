package com.lethio.macros

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The food-name language is derived from the database, not asked as its own question.
 *
 * The language *lists* here are what `TableLanguages` returns for real tables, measured against
 * build 10 — so these assert the RULE applied to real inputs. That the Swiss table really does
 * carry `it` and `fr` on ~97% of its rows is a property of the data and is asserted against the
 * shipped database in the instrumented suite, not here.
 */
class DerivedFoodLanguagesTest {

    private val swiss = listOf("de", "en", "it", "fr")
    private val fineli = listOf("fi", "en", "sv")
    private val cnf = listOf("en", "fr")
    private val tca = listOf("pt")
    private val bedca = listOf("es", "en")

    @Test
    fun `the database's own language leads when the reader's is not served`() {
        // A Norwegian reader on the Spanish table: `nb` is not in BEDCA at all, so the table's
        // own order stands and nothing invented is promoted above it.
        assertThat(SettingsManager.derivedFoodLanguages("", "nb", bedca))
            .containsExactly("es", "en")
            .inOrder()
    }

    @Test
    fun `an English reader on a national table gets its English aliases first`() {
        // The expat case, and the reason the reader's language is checked before the table's own.
        // BEDCA is Spanish-named but carries English aliases on effectively every row, so an
        // English reader should be shown those rather than the Spanish names.
        assertThat(SettingsManager.derivedFoodLanguages("", "en", bedca))
            .containsExactly("en", "es")
            .inOrder()
    }

    @Test
    fun `a reader's language wins when their table serves it`() {
        // The Swiss case, without a rule about Switzerland. A francophone gets French first
        // because the table carries French on 996 of its 1,031 rows.
        assertThat(SettingsManager.derivedFoodLanguages("", "fr", swiss).first()).isEqualTo("fr")
        assertThat(SettingsManager.derivedFoodLanguages("", "it", swiss).first()).isEqualTo("it")
        // ...and a German speaker gets the table's own name language, which is already German.
        assertThat(SettingsManager.derivedFoodLanguages("", "de", swiss).first()).isEqualTo("de")
    }

    @Test
    fun `a reader's language is not invented when their table does not serve it`() {
        // A Norwegian who has chosen the Swiss database is searching a table with no Norwegian
        // in it. Putting `nb` first would promote a language that cannot match a single row.
        assertThat(SettingsManager.derivedFoodLanguages("", "nb", swiss))
            .containsExactly("de", "en", "it", "fr")
            .inOrder()
    }

    @Test
    fun `Finland serves a Finn and a Finland-Swede from the same table`() {
        assertThat(SettingsManager.derivedFoodLanguages("", "fi", fineli).first()).isEqualTo("fi")
        assertThat(SettingsManager.derivedFoodLanguages("", "sv", fineli).first()).isEqualTo("sv")
        // Either way both languages stay available, because the table is written in both.
        assertThat(SettingsManager.derivedFoodLanguages("", "sv", fineli)).contains("fi")
    }

    @Test
    fun `Canada serves its second language`() {
        assertThat(SettingsManager.derivedFoodLanguages("", "fr", cnf))
            .containsExactly("fr", "en")
            .inOrder()
    }

    @Test
    fun `Portugal gets no English, because TCA has none`() {
        // The case that shows this is a derivation and not a rule. The old code appended `en`
        // unconditionally, claiming a fallback that does not exist: TCA carries no English name
        // for any of its 1,330 rows. Display falls back to the Portuguese name via COALESCE.
        assertThat(SettingsManager.derivedFoodLanguages("", "en", tca)).containsExactly("pt")
    }

    @Test
    fun `an explicit stored choice outranks the derived default`() {
        // Anyone who picked food-name languages made a real choice, and a choice beats a default
        // derived on their behalf.
        assertThat(SettingsManager.derivedFoodLanguages("nb,sv", "en", swiss))
            .containsExactly("nb", "sv", "en")
            .inOrder()
    }

    @Test
    fun `an unmapped country falls back to the app language`() {
        // No national table, so nothing to derive from: the search covers every generic source
        // and the old app-language behaviour is still the right one.
        assertThat(SettingsManager.derivedFoodLanguages("", "nl", emptyList()))
            .containsExactly("nl", "en")
            .inOrder()
    }

    @Test
    fun `no language is ever listed twice`() {
        assertThat(SettingsManager.derivedFoodLanguages("", "en", cnf))
            .containsExactly("en", "fr")
            .inOrder()
        assertThat(SettingsManager.derivedFoodLanguages("en", "en", cnf)).containsExactly("en")
    }
}
