package com.lethio.macros.data.food

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Search runs on every keystroke, so every one of these inputs is something a user will actually
 * produce mid-word. An unhandled FTS5 operator here is a crash on the search screen.
 */
class FtsQueryTest {

    @Test
    fun `plain single word becomes a quoted prefix term`() {
        assertThat(FtsQuery.sanitize("chicken")).isEqualTo("\"chicken\"*")
    }

    @Test
    fun `multiple words each become prefix terms`() {
        assertThat(FtsQuery.sanitize("chick br")).isEqualTo("\"chick\"* \"br\"*")
    }

    @Test
    fun `apostrophes are stripped, not escaped`() {
        // A bare apostrophe is an FTS5 syntax error. "Ben & Jerry's" is a real product name.
        assertThat(FtsQuery.sanitize("it's")).isEqualTo("\"it\"* \"s\"*")
        assertThat(FtsQuery.sanitize("Ben & Jerry's")).isEqualTo("\"ben\"* \"jerry\"* \"s\"*")
    }

    @Test
    fun `double quotes cannot escape the quoting`() {
        assertThat(FtsQuery.sanitize("say \"hi\"")).isEqualTo("\"say\"* \"hi\"*")
        assertThat(FtsQuery.sanitize("\"")).isNull()
    }

    @Test
    fun `FTS5 operators are treated as separators, never as syntax`() {
        assertThat(FtsQuery.sanitize("a*b")).isEqualTo("\"a\"* \"b\"*")
        assertThat(FtsQuery.sanitize("a+b")).isEqualTo("\"a\"* \"b\"*")
        assertThat(FtsQuery.sanitize("a-b")).isEqualTo("\"a\"* \"b\"*")
        assertThat(FtsQuery.sanitize("a:b")).isEqualTo("\"a\"* \"b\"*")
        assertThat(FtsQuery.sanitize("a^b")).isEqualTo("\"a\"* \"b\"*")
        assertThat(FtsQuery.sanitize("(a)")).isEqualTo("\"a\"*")
    }

    @Test
    fun `boolean keywords are matched as literal text, not as operators`() {
        // Without quoting, "milk OR bread" would run as a disjunction and "NOT" would negate.
        assertThat(FtsQuery.sanitize("milk OR bread")).isEqualTo("\"milk\"* \"or\"* \"bread\"*")
        assertThat(FtsQuery.sanitize("NOT milk")).isEqualTo("\"not\"* \"milk\"*")
    }

    @Test
    fun `empty and punctuation-only input yields null so the caller skips the query`() {
        assertThat(FtsQuery.sanitize("")).isNull()
        assertThat(FtsQuery.sanitize("   ")).isNull()
        assertThat(FtsQuery.sanitize("***")).isNull()
        assertThat(FtsQuery.sanitize("-")).isNull()
        assertThat(FtsQuery.sanitize("!@#$%^&*()")).isNull()
    }

    @Test
    fun `digits survive, since barcodes and quantities are searched`() {
        assertThat(FtsQuery.sanitize("cola 330")).isEqualTo("\"cola\"* \"330\"*")
    }

    @Test
    fun `non-latin scripts survive`() {
        // The app ships in 10 locales including Ukrainian; stripping to ASCII would make search
        // useless for those users.
        assertThat(FtsQuery.sanitize("молоко")).isEqualTo("\"молоко\"*")
    }

    @Test
    fun `the query is folded, because the index holds folded names`() {
        // `food_search` indexes `foods.name_folded`. These two are one comparison and the pair
        // has to move together: a folded index matched against a raw query is a regression, not
        // a no-op. Accents were already handled on both sides by `remove_diacritics 2`, so the
        // case that actually changes is the ligature below.
        assertThat(FtsQuery.sanitize("crème fraîche")).isEqualTo("\"creme\"* \"fraiche\"*")
        assertThat(FtsQuery.sanitize("Müsli")).isEqualTo("\"musli\"*")
    }

    @Test
    fun `ligatures are split, so the ligature key reaches the folded name`() {
        // Unicode calls œ and ß letters in their own right rather than accented forms, so the
        // tokenizer leaves them intact and no amount of diacritic removal splits them. Ciqual's
        // raw egg is "Oeuf, cru" and a German loaf is "Weißbrot"; both are stored folded, so
        // typing the ligature has to fold to the same thing to reach them.
        assertThat(FtsQuery.sanitize("Œuf")).isEqualTo("\"oeuf\"*")
        assertThat(FtsQuery.sanitize("Weißbrot")).isEqualTo("\"weissbrot\"*")
    }

    @Test
    fun `every token agrees with foldForSearch, which is what the index was built with`() {
        // The real invariant is not any single expected string: it is that nothing sanitize()
        // emits could have been produced by a different folding than the pipeline used. Anything
        // that folds one side and not the other degrades ranking silently, with nothing failing.
        listOf(
                "crème fraîche",
                "Œuf, cru",
                "Weißbrot",
                "MÜSLI",
                "Jamón serrano",
                "Hähnchenbrust",
                "café-au-lait",
                "Smør",
                "Łódź",
            )
            .forEach { input ->
                val folded = foldForSearch(input)
                FtsQuery.sanitize(input)!!.split(" ")
                    .map { it.removeSurrounding("\"", "\"*") }
                    .forEach { token -> assertThat(folded).contains(token) }
            }
    }

    @Test
    fun `an absurdly long token is truncated rather than passed through`() {
        val result = FtsQuery.sanitize("a".repeat(500))
        assertThat(result).isNotNull()
        assertThat(result!!.length).isLessThan(80)
    }

    @Test
    fun `token count is capped`() {
        val result = FtsQuery.sanitize((1..40).joinToString(" ") { "word$it" })
        assertThat(result!!.split(" ")).hasSize(8)
    }

    @Test
    fun `output never contains an unbalanced quote`() {
        listOf("it's", "a\"b", "\"\"\"", "milk\"", "a'b\"c*d").forEach { input ->
            val out = FtsQuery.sanitize(input) ?: return@forEach
            assertThat(out.count { it == '"' } % 2).isEqualTo(0)
        }
    }

    // ---- LIKE's wildcards, on both halves of one comparison ----

    @Test
    fun `escaping neutralises the wildcards that used to return the whole shelf`() {
        // The defect: no ESCAPE clause anywhere on the custom-food path, so typing `%` matched
        // every row and crowded every bundled result out of the merged list.
        assertThat(escapeForLike("%")).isEqualTo("\\%")
        assertThat(escapeForLike("k_ffe")).isEqualTo("k\\_ffe")
        assertThat(escapeForLike("50%")).isEqualTo("50\\%")
    }

    @Test
    fun `the escape character escapes itself, and does so first`() {
        // Order matters: escaping % before \ would re-escape the backslash just written and turn
        // one literal backslash into a wildcard escape for nothing.
        assertThat(escapeForLike("\\")).isEqualTo("\\\\")
        assertThat(escapeForLike("a\\%b")).isEqualTo("a\\\\\\%b")
    }

    @Test
    fun `escaping leaves ordinary text completely alone`() {
        listOf("kaffe", "Bjorn & Sonn", "creme fraiche 34", "it's", "oeuf, cru").forEach {
            assertThat(escapeForLike(it)).isEqualTo(it)
        }
    }

    @Test
    fun `stripping discards the wildcards rather than escaping them`() {
        // plainQuery sits behind an FTS5 MATCH that sanitize has already stripped to
        // alphanumerics, so it must look for what the candidate set was actually selected on.
        assertThat(stripLikeWildcards("mil_")).isEqualTo("mil")
        assertThat(stripLikeWildcards("mi%k")).isEqualTo("mik")
        assertThat(stripLikeWildcards("milk")).isEqualTo("milk")
    }

    @Test
    fun `the two halves agree with sanitize about what a wildcard is`() {
        // The rule FtsQuery's KDoc states: the two sides are one comparison. sanitize keeps only
        // letters and digits, so neither wildcard can survive to the MATCH side; stripLikeWildcards
        // must not leave one on the ladder side either.
        listOf("mil_", "mi%k", "100%", "a_b%c").forEach { raw ->
            val stripped = stripLikeWildcards(foldForSearch(raw))
            assertThat(stripped).doesNotContain("%")
            assertThat(stripped).doesNotContain("_")
        }
    }

    @Test
    fun `folding then escaping is what the custom shelf receives`() {
        // The exact composition FoodRepositoryImpl applies. A user's own `Kaffe` with a grave
        // accent has to be reachable by typing plain ascii.
        assertThat(escapeForLike(foldForSearch("Kaffè"))).isEqualTo("kaffe")
        assertThat(escapeForLike(foldForSearch("Bjørn"))).isEqualTo("bjorn")
        assertThat(escapeForLike(foldForSearch("50%"))).isEqualTo("50\\%")
    }
}
