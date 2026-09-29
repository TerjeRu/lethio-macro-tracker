package com.lethio.macros.data.food

fun escapeForLike(input: String): String = input
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

fun stripLikeWildcards(input: String): String = input.filterNot { it == '%' || it == '_' }

object FtsQuery {

    private const val MAX_TOKEN_LENGTH = 64

    private const val MAX_TOKENS = 8

    fun sanitize(raw: String): String? {
        val tokens = foldForSearch(raw)

            .split(*SEPARATORS)
            .asSequence()
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_TOKEN_LENGTH) }
            .take(MAX_TOKENS)
            .toList()

        if (tokens.isEmpty()) return null

        return tokens.joinToString(" ") { "\"$it\"*" }
    }

    private val COMPOUND_LANGS = setOf("da", "nb", "sv", "de", "fi")

    private const val MIN_HEAD = 4
    private const val MIN_STEM = 3

    private val LINKS = arrayOf("", "s", "es", "n", "en", "e", "er")

    private val PLURAL_SUFFIXES = arrayOf("s", "es", "t", "er", "en", "ter", "n")

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

    fun depluralize(token: String, lexicon: Set<String>): String? {
        if (token in lexicon) return null
        for (suffix in PLURAL_SUFFIXES) {
            if (!token.endsWith(suffix)) continue
            val stem = token.dropLast(suffix.length)
            if (stem.length >= MIN_STEM && stem in lexicon) return stem
        }
        return null
    }

    fun rescueVariant(
        plainQuery: String,
        source: String,
        lexicons: Map<String, CompoundLexicon.Entry>,
    ): Rescue? {
        if (plainQuery.isEmpty() || !plainQuery.all(Char::isLetterOrDigit)) return null

        val entry = lexicons[source] ?: return null

        if (entry.lang in COMPOUND_LANGS) {
            val split = splitCompound(plainQuery, entry.words)
            if (split != null) return Rescue(query = split.first, head = split.second)
        }
        val stem = depluralize(plainQuery, entry.words)
        if (stem != null) return Rescue(query = stem, head = "")
        return null
    }

    data class Rescue(val query: String, val head: String)

    private val SEPARATORS: Array<String> = arrayOf(
        " ", "\t", "\n", "\r", ",", ";", ".", "/", "\\", "|", "-", "_",
        "(", ")", "[", "]", "{", "}", ":", "*", "+", "^", "\"", "'", "`", "!", "?", "&", "%", "#",
    )
}
