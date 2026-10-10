package com.lethio.macros.data.food

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each national table's vocabulary, for [FtsQuery.splitCompound] and [FtsQuery.depluralize] to check
 * candidate words against. Built by `build_nutrition_db.py` into `compound-lexicon.txt` from the same
 * names the index uses.
 *
 * Keyed per table, because the reader searches one table: per language, a word could be confirmed
 * by a table the reader is not searching. The language is kept for the compound-splitting gate.
 * Loaded lazily, only when a rescue is attempted.
 */
@Singleton
class CompoundLexicon @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One table's vocabulary, and the language it is written in. */
    data class Entry(val lang: String, val words: Set<String>)

    @Volatile
    private var lexicons: Map<String, Entry>? = null

    /** {source: (language, words)}; empty if the asset is missing, which disables rescues rather than search. */
    suspend fun lexicons(): Map<String, Entry> =
        lexicons ?: withContext(Dispatchers.IO) { load() }.also { lexicons = it }

    private fun load(): Map<String, Entry> = runCatching {
        val langs = mutableMapOf<String, String>()
        val words = mutableMapOf<String, MutableSet<String>>()
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                // A stray CR from a CRLF checkout would make every lookup silently miss.
                val parts = raw.trimEnd('\r', '\n').split('\t')
                if (parts.size == 3 && parts[0].isNotEmpty() && parts[2].isNotEmpty()) {
                    langs[parts[0]] = parts[1]
                    words.getOrPut(parts[0]) { mutableSetOf() }.add(parts[2])
                }
            }
        }
        words.mapValues { (source, set) -> Entry(langs.getValue(source), set) }
    }.getOrDefault(emptyMap())

    private companion object {
        const val ASSET = "compound-lexicon.txt"
    }
}
