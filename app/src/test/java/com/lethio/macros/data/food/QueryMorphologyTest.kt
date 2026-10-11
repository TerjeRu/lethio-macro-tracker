package com.lethio.macros.data.food

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The query-side rescue: splitting a compound the reader typed, and finding the singular of a
 * plural they typed.
 *
 * The lexicons here are small hand-written sets, not the shipped asset, so these assert the RULE.
 * Whether the shipped lexicon actually contains `svin` is a property of the data and is measured
 * separately -- notably it does NOT contain a Danish `svin`, which is why `svinekød` cannot be
 * rescued and why that is a data gap rather than a threshold to tune.
 */
class QueryMorphologyTest {

    private val nb = setOf("svin", "kjott", "storfe", "kylling", "filet", "olje", "oliven")
    private val es = setOf("lenteja", "huevo", "tomate", "garbanzo")
    private val fi = setOf("linssi", "porkkana", "oliivi", "oljy")

    /**
     * One table's entry, as [CompoundLexicon] hands it over: source -> (language, vocabulary). The
     * tests address a SOURCE, because that is the question the rescue asks -- "is this a word the
     * table the reader chose knows", not "does anyone writing this language use it".
     */
    private fun lexicon(vararg entries: Triple<String, String, Set<String>>) =
        entries.associate { (source, lang, words) -> source to CompoundLexicon.Entry(lang, words) }

    @Test
    fun `a compound of two known words splits into stem and head`() {
        assertThat(FtsQuery.splitCompound("svinekjott", nb)).isEqualTo("svin" to "kjott")
        assertThat(FtsQuery.splitCompound("storfekjott", nb)).isEqualTo("storfe" to "kjott")
        assertThat(FtsQuery.splitCompound("kyllingfilet", nb)).isEqualTo("kylling" to "filet")
    }

    @Test
    fun `the linking morpheme is stripped from the stem`() {
        // svine+kjott: `svine` is not a word, `svin` is, and `e` is what joins them.
        assertThat(FtsQuery.splitCompound("svinekjott", nb)?.first).isEqualTo("svin")
    }

    @Test
    fun `both halves must be words, so an ending is not a head`() {
        // The guard that matters. Ungated, the German diminutive `chen` was the single most
        // frequently "recovered" head, from Hähnchen -- `hahn` is not in this lexicon either.
        assertThat(FtsQuery.splitCompound("kyllingfilet", setOf("filet"))).isNull()
        assertThat(FtsQuery.splitCompound("kyllingfilet", setOf("kylling"))).isNull()
    }

    @Test
    fun `a word that is not a compound does not split`() {
        assertThat(FtsQuery.splitCompound("kylling", nb)).isNull()
        assertThat(FtsQuery.splitCompound("melk", nb)).isNull()
    }

    @Test
    fun `a plural becomes the singular the table actually stores`() {
        assertThat(FtsQuery.depluralize("lentejas", es)).isEqualTo("lenteja")
        assertThat(FtsQuery.depluralize("huevos", es)).isEqualTo("huevo")
        assertThat(FtsQuery.depluralize("tomates", es)).isEqualTo("tomate")
        assertThat(FtsQuery.depluralize("linssit", fi)).isEqualTo("linssi")
        assertThat(FtsQuery.depluralize("porkkanat", fi)).isEqualTo("porkkana")
    }

    @Test
    fun `the Nordic umlaut plural folds to a stem the data has`() {
        // morötter folds to morotter, and the singular is morot -- neither `s` nor `t` reaches it.
        assertThat(FtsQuery.depluralize("morotter", setOf("morot"))).isEqualTo("morot")
        assertThat(FtsQuery.depluralize("gulrotter", setOf("gulrot"))).isEqualTo("gulrot")
    }

    @Test
    fun `a stem the data does not have is never invented`() {
        // No table anywhere holds a row called Zanahoria, so the right answer is to do nothing
        // rather than run a confident query for a food that is not there.
        assertThat(FtsQuery.depluralize("zanahorias", es)).isNull()
        assertThat(FtsQuery.depluralize("naranjas", es)).isNull()
    }

    @Test
    fun `a query that is already a word is left alone`() {
        assertThat(FtsQuery.depluralize("lenteja", es)).isNull()
    }

    @Test
    fun `splitting is only attempted for the compounding languages`() {
        val lexicons = lexicon(Triple("bedca", "es", es + setOf("huevotomate")))
        // Spanish does not compound; the rescue must not try to split Spanish even if the halves
        // happen to be words.
        assertThat(FtsQuery.rescueVariant("huevotomate", "bedca", lexicons)).isNull()
    }

    @Test
    fun `the compound split is tried before the plural`() {
        // `kyllingfilet` is splittable; nothing may read it as a plural of `kyllingfile`.
        val rescue =
            FtsQuery.rescueVariant(
                "kyllingfilet",
                "matvaretabellen",
                lexicon(Triple("matvaretabellen", "nb", nb)),
            )
        assertThat(rescue).isEqualTo(FtsQuery.Rescue("kylling", "filet"))
    }

    @Test
    fun `a plural rescue carries no head word, so the ranking arm stays inert`() {
        val rescue =
            FtsQuery.rescueVariant("linssit", "fineli", lexicon(Triple("fineli", "fi", fi)))
        assertThat(rescue).isEqualTo(FtsQuery.Rescue("linssi", ""))
    }

    @Test
    fun `a multi-word query is never rescued`() {
        // The rescue reads one token. `carne de vaca` is a coverage gap, not a morphology one.
        val bedca = lexicon(Triple("bedca", "es", es))
        assertThat(FtsQuery.rescueVariant("carne de vaca", "bedca", bedca)).isNull()
        assertThat(FtsQuery.rescueVariant("", "bedca", bedca)).isNull()
    }

    @Test
    fun `an empty lexicon rescues nothing`() {
        // The asset failing to open must leave search exactly as it was, not crash it.
        assertThat(FtsQuery.rescueVariant("svinekjott", "matvaretabellen", emptyMap())).isNull()
    }

    @Test
    fun `another table's vocabulary cannot block this table's rescue`() {
        // `lentils` is a word in USDA, so keyed per language the guard in depluralize would refuse
        // to reduce it -- and an Australian, whose table writes `Lentil`,
        // would get nothing. Keyed per source, USDA's vocabulary is simply not consulted.
        val lexicons =
            lexicon(
                Triple("afcd", "en", setOf("lentil", "carrot")),
                Triple("usda", "en", setOf("lentil", "lentils", "carrot")),
            )
        assertThat(FtsQuery.rescueVariant("lentils", "afcd", lexicons))
            .isEqualTo(FtsQuery.Rescue("lentil", ""))
        // And USDA, which really does write the plural, is still left alone.
        assertThat(FtsQuery.rescueVariant("lentils", "usda", lexicons)).isNull()
    }

    @Test
    fun `a query in a language the table does not use is not rescued`() {
        // `languages_for("es")` appends `en`, so keyed per language a Spanish reader's query would
        // be rewritten against the English vocabulary -- and because the rewrite applies to the
        // fallback shelf too, correct results would be replaced with worse ones. One table, one
        // vocabulary, so English `oats` simply finds no rescue on BEDCA.
        val lexicons =
            lexicon(Triple("bedca", "es", es), Triple("usda", "en", setOf("oat", "oats")))
        assertThat(FtsQuery.rescueVariant("oats", "bedca", lexicons)).isNull()
    }

    @Test
    fun `an unmapped country has no table, so nothing is rescued`() {
        // A reader with no national table searches every generic source; there is no single
        // vocabulary to ask, and the empty source must return null rather than throw.
        assertThat(FtsQuery.rescueVariant("linssit", "", lexicon(Triple("fineli", "fi", fi))))
            .isNull()
    }

    @Test
    fun `bare n reaches the German feminine plural`() {
        // Linse -> Linsen. Without it a Swiss reader's `linsen` reached nothing at all.
        val lexicons = lexicon(Triple("swiss", "de", setOf("linse", "gurke")))
        assertThat(FtsQuery.rescueVariant("linsen", "swiss", lexicons))
            .isEqualTo(FtsQuery.Rescue("linse", ""))
    }
}
