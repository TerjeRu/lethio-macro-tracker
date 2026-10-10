package com.lethio.macros.data.food

/**
 * Escapes `LIKE` wildcards and the escape character for `ESCAPE '\'`.
 *
 * The custom-food search reaches `LIKE` directly, so the literal query must be honoured: `50%`
 * means `50%`. Contrast [stripLikeWildcards]. The backslash is escaped first.
 */
fun escapeForLike(input: String): String = input
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/**
 * Drops `LIKE` wildcards from a query that has been through [FtsQuery.sanitize], which already
 * discarded them on the `MATCH` side, so both halves of the comparison look for the same thing.
 */
fun stripLikeWildcards(input: String): String = input.filterNot { it == '%' || it == '_' }

/**
 * Turns typed text into a safe FTS5 `MATCH` expression.
 *
 * Raw input must never reach `MATCH`: an apostrophe, a quote or `-` is FTS5 syntax, and search runs
 * on every half-typed keystroke. Tokens are folded with [foldForSearch] (the index holds folded
 * names), stripped to letters and digits, quoted and prefixed: `chick br` becomes `"chick"* "br"*`.
 */
object FtsQuery {

    /** Longer than any real food word; guards against a pathological single token. */
    private const val MAX_TOKEN_LENGTH = 64

    /** Beyond this, extra terms only narrow an already-narrow query. */
    private const val MAX_TOKENS = 8

    /**
     * @return a `MATCH` expression, or null when the input has no searchable content — in which
     *   case the caller must skip the query rather than run an empty one.
     */
    fun sanitize(raw: String): String? {
        val tokens = foldForSearch(raw)
            // Letters and digits in any script: Cyrillic and Greek names must survive.
            .split(*SEPARATORS)
            .asSequence()
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_TOKEN_LENGTH) }
            .take(MAX_TOKENS)
            .toList()

        if (tokens.isEmpty()) return null

        // Quoted as literals; no token can contain a quote, so nothing needs escaping.
        return tokens.joinToString(" ") { "\"$it\"*" }
    }

    /** Languages that write the basic food as a compound, as `COMPOUND_LANGS` in the build. */
    private val COMPOUND_LANGS = setOf("da", "nb", "sv", "de", "fi")

    /** Minimum head and stem length; the head minimum matches the build's `COMPOUND_MIN_HEAD`. */
    private const val MIN_HEAD = 4
    private const val MIN_STEM = 3

    /** Germanic linking morphemes, as the build side's `COMPOUND_LINKS`. */
    private val LINKS = arrayOf("", "s", "es", "n", "en", "e", "er")

    /**
     * Plural endings to try. Safe to be generous because every stem must be a lexicon word. `ter`
     * covers the folded Nordic umlaut plural (`morotter` to `morot`); `n` the German `Linsen`.
     */
    private val PLURAL_SUFFIXES = arrayOf("s", "es", "t", "er", "en", "ter", "n")

    /**
     * Splits a compound query into (stem, head), or null. The head is the suffix, as in Germanic
     * and Finnish (`svine`+`kjøtt`), longest head first. Both halves must be words in the reader's
     * own table; without that guard German `Hähnchen` yields the head `chen`.
     */
    fun splitCompound(token: String, lexicon: Set<String>): Pair<String, String>? {
        for (cut in 1..token.length - MIN_HEAD) {
            val head = token.substring(cut)
            if (head !in lexicon) continue
            val rest = token.substring(0, cut)
            for (link in LINKS) {
                val stem = if (link.isNotEmpty() && rest.endsWith(link)) {
                    rest.dropLast(link.length)
                } else {
                    rest
                }
                if (stem.length >= MIN_STEM && stem in lexicon) return stem to head
            }
        }
        return null
    }

    /** The singular of a plural query, or null. Returns only forms the data contains. */
    fun depluralize(token: String, lexicon: Set<String>): String? {
        if (token in lexicon) return null
        for (suffix in PLURAL_SUFFIXES) {
            if (!token.endsWith(suffix)) continue
            val stem = token.dropLast(suffix.length)
            if (stem.length >= MIN_STEM && stem in lexicon) return stem
        }
        return null
    }

    /**
     * What to search instead when the reader's table had no head match: a compound split, else a
     * singular. The split goes first because it is the stricter test. [Rescue.head] is empty when
     * nothing split.
     */
    fun rescueVariant(
        plainQuery: String,
        source: String,
        lexicons: Map<String, CompoundLexicon.Entry>,
    ): Rescue? {
        if (plainQuery.isEmpty() || !plainQuery.all(Char::isLetterOrDigit)) return null

        // The vocabulary of the one table that failed to answer, not the reader's whole language.
        val entry = lexicons[source] ?: return null

        if (entry.lang in COMPOUND_LANGS) {
            val split = splitCompound(plainQuery, entry.words)
            if (split != null) return Rescue(query = split.first, head = split.second)
        }
        val stem = depluralize(plainQuery, entry.words)
        if (stem != null) return Rescue(query = stem, head = "")
        return null
    }

    /** A second query to try, with the head word that [FoodDao] ranks on. */
    data class Rescue(val query: String, val head: String)

    private val SEPARATORS: Array<String> = arrayOf(
        " ", "\t", "\n", "\r", ",", ";", ".", "/", "\\", "|", "-", "_",
        "(", ")", "[", "]", "{", "}", ":", "*", "+", "^", "\"", "'", "`", "!", "?", "&", "%", "#",
    )
}
