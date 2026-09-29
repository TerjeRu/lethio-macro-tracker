package com.lethio.macros.data.food

import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef

internal fun <T, K> composeSearchResults(
    primary: List<T>,
    fallback: List<T>,
    branded: List<T>,
    limit: Int,
    keyOf: (T) -> K,
    tierOf: (T) -> Int = { HEAD_MATCH_TIER },
): List<T> {
    val generic = primary.map { it to PRIMARY_SHELF } + fallback.map { it to FALLBACK_SHELF }
    val merged = generic
        .sortedWith(
            compareBy(
                { (row, _) -> if (tierOf(row) <= HEAD_MATCH_TIER) 0 else 1 },
                { (_, shelf) -> shelf },
                { (row, _) -> tierOf(row) },
            )
        )
        .map { (row, _) -> row }
    return (merged + branded)
        .distinctBy(keyOf)
        .take(limit.coerceAtLeast(0))
}

private const val PRIMARY_SHELF = 0
private const val FALLBACK_SHELF = 1

internal const val HEAD_MATCH_TIER = 2

internal fun boostKnownFoods(
    results: List<Food>,
    favoriteRefs: Set<FoodRef>,
    recentRefs: Set<FoodRef>,
): List<Food> {
    if (favoriteRefs.isEmpty() && recentRefs.isEmpty()) return results
    val (favorites, remainder) = results.partition { it.ref in favoriteRefs }
    val (recents, unknown) = remainder.partition { it.ref in recentRefs }
    return favorites + recents + unknown
}
