package com.lethio.macros.data.food

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompoundLexicon @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Entry(val lang: String, val words: Set<String>)

    @Volatile
    private var lexicons: Map<String, Entry>? = null

    suspend fun lexicons(): Map<String, Entry> =
        lexicons ?: withContext(Dispatchers.IO) { load() }.also { lexicons = it }

    private fun load(): Map<String, Entry> = runCatching {
        val langs = mutableMapOf<String, String>()
        val words = mutableMapOf<String, MutableSet<String>>()
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.forEach { raw ->

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
