package com.lethio.macros.data.food

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which languages a national table is written in, read from the table rather than a hand-kept map.
 *
 * Swiss: `de`, `en`, `it`, `fr`. Fineli: `fi`, `en`, `sv`. CNF: `en`, `fr`. TCA: `pt` only, with no
 * English fallback. Every other table: its own language, then `en`. Cached for the process; the
 * bundled table is read-only.
 */
@Singleton
class TableLanguages @Inject constructor(
    private val foodDatabase: FoodDatabaseProvider,
) {
    private val cache = mutableMapOf<String, List<String>>()
    private val mutex = Mutex()

    @Volatile
    private var allSources: Map<String, List<String>>? = null

    /** The languages [source] serves, own language first; empty for an unmapped country (`""`). */
    suspend fun forSource(source: String): List<String> {
        if (source.isEmpty()) return emptyList()
        cache[source]?.let { return it }
        return mutex.withLock {
            cache[source] ?: foodDatabase.dao()
                .languagesServedBy(source, MIN_ALIAS_COVERAGE_PERCENT)
                .also { cache[source] = it }
        }
    }

    /**
     * Tables to borrow from for a reader of [languages]: any table serving one of them, except
     * their own. Borrowing along language gives a Swiss reader BLS's *Gurke roh*, not a Finnish
     * crispbread for a Norwegian's `havre`.
     */
    suspend fun sourcesServing(languages: List<String>, excluding: String): List<String> {
        val wanted = languages.toSet()
        return all().filterKeys { it != excluding }
            .filterValues { served -> served.any { it in wanted } }
            .keys.sorted()
    }

    private suspend fun all(): Map<String, List<String>> =
        allSources ?: mutex.withLock {
            allSources ?: foodDatabase.dao()
                .languagesBySource(MIN_ALIAS_COVERAGE_PERCENT)
                .groupBy({ it.source }, { it.lang })
                .also { allSources = it }
        }

    companion object {
        /**
         * An alias language must cover this share of a table to count. Real coverages are 97-100%
         * or far below, so any value between about 20 and 95 gives the same answer.
         */
        const val MIN_ALIAS_COVERAGE_PERCENT = 50
    }
}
