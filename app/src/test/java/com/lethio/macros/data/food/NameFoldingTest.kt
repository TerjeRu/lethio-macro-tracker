package com.lethio.macros.data.food

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [foldForSearch] to the same fixtures as `fold_name()` in `tools/build_nutrition_db.py`.
 *
 * The two implementations sit on opposite sides of the same comparison — Python folds the name
 * stored in `foods.name_folded`, Kotlin folds the user's query — so if they ever disagree, search
 * ranking degrades for exactly the accented languages the folding was added for, and **nothing
 * fails**. Results merely get quietly worse. A shared fixture file is what makes that drift
 * visible: `tools/test_name_folding.py` asserts the same rows from the same file.
 */
class NameFoldingTest {

    private data class Fixture(val input: String, val expected: String)

    private val fixtures: List<Fixture> by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/$FIXTURE_FILE")) {
                    "$FIXTURE_FILE is missing from the test resources"
                }
                .bufferedReader()
                .readText()

        text
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size == 2) { "malformed fixture line: $line" }
                Fixture(parts[0], parts[1])
            }
            .toList()
    }

    @Test
    fun `agrees with the pipeline on every shared fixture`() {
        assertThat(fixtures).isNotEmpty()
        fixtures.forEach { (input, expected) ->
            assertThat(foldForSearch(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `accents fold to their base letters`() {
        assertThat(foldForSearch("ÉPINARDS")).isEqualTo("epinards")
        assertThat(foldForSearch("crème")).isEqualTo("creme")
        assertThat(foldForSearch("Müsli")).isEqualTo("musli")
        assertThat(foldForSearch("Piña")).isEqualTo("pina")
    }

    @Test
    fun `ligatures expand to the two letters people actually type`() {
        // Ciqual's real name for a raw egg is "Œuf, cru". NFKD does not split the ligature, so
        // without this nobody typing "oeuf" — which is everybody — could reach it.
        assertThat(foldForSearch("Œuf, cru")).isEqualTo("oeuf, cru")
        assertThat(foldForSearch("Weißbrot")).isEqualTo("weissbrot")
    }

    @Test
    fun `letters carrying a stroke fold to their base letter`() {
        // These are letters in their own right rather than accented forms, but leaving them alone
        // makes a third of the Norwegian and Danish alias layer unreachable to anyone without a
        // Nordic keyboard. NFKD already strips the ring from "a" and the ae ligature already folds,
        // so "blabar" reached blaabaer while "rodbete" reached nothing.
        //
        // The base letter, not the traditional digraph: o-slash to "o" rather than "oe", because
        // a-ring already folds to "a" rather than "aa".
        assertThat(foldForSearch("Smør")).isEqualTo("smor")
        assertThat(foldForSearch("Rødbete")).isEqualTo("rodbete")
        assertThat(foldForSearch("Łódź")).isEqualTo("lodz")
        assertThat(foldForSearch("Flødeið")).isEqualTo("flodeid")
    }

    @Test
    fun `a digraph still expands, a stroke still does not`() {
        // The two groups must not collapse into one rule: the ae ligature is two letters and
        // folds to two, while o-slash is one letter with a stroke and folds to one.
        assertThat(foldForSearch("Ærøskøbing")).isEqualTo("aeroskobing")
    }

    @Test
    fun `punctuation survives, because the ranking depends on it`() {
        // The tiers turn on the comma in "Butter, salted" — the USDA and Ciqual convention that a
        // comma means qualifiers follow and a space means a compound noun. Stripping punctuation
        // here would silently delete what separates butter from butter beans.
        assertThat(foldForSearch("Butter, salted")).isEqualTo("butter, salted")
        assertThat(foldForSearch("café-au-lait")).isEqualTo("cafe-au-lait")
    }

    @Test
    fun `folding is idempotent`() {
        // The query is folded once per search and the name once per build; if a second pass
        // changed anything, the two sides would depend on how many times each had been applied.
        fixtures.forEach { (input, _) ->
            val once = foldForSearch(input)
            assertThat(foldForSearch(once)).isEqualTo(once)
        }
    }

    private companion object {
        const val FIXTURE_FILE = "name-folding-fixtures.tsv"
    }
}
