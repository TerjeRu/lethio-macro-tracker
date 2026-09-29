package com.lethio.macros.data.food

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TableLanguages @Inject constructor(
    private val foodDatabase: FoodDatabaseProvider,
) {
    private val cache = mutableMapOf<String, List<String>>()
    private val mutex = Mutex()

    @Volatile
    private var allSources: Map<String, List<String>>? = null

    suspend fun forSource(source: String): List<String> {
        if (source.isEmpty()) return emptyList()
        cache[source]?.let { return it }
        return mutex.withLock {
            cache[source] ?: foodDatabase.dao()
                .languagesServedBy(source, MIN_ALIAS_COVERAGE_PERCENT)
                .also { cache[source] = it }
        }
    }

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

        const val MIN_ALIAS_COVERAGE_PERCENT = 50
    }
}
