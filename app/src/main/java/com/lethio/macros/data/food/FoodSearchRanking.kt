package com.lethio.macros.data.food

import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef

/**
 * Merges the ranked shelves from [FoodRepositoryImpl]: generic before branded, deduplicated before
 * the limit.
 *
 * The national shelf keeps the answer when it has a head match ([HEAD_MATCH_TIER] or better) and
 * yields otherwise: head matches first, national before fallback, then the rest by tier. A raw-tier
 * merge was measured and rejected, because tiers encode each table's punctuation (Swedish
 * `Bröd vitt` is tier 2, Norwegian `Brød, grovt` tier 1) and let false friends win (`Ris, veau` is
 * French for sweetbread).
 *
 * Both sorts are stable, so within a bucket the SQL ladder's order stands. Branded products are
 * appended last.
 */
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

/**
 * The worst tier that counts as the reader's table having answered: the query followed by a space at
 * the head of the name. Tiers 3-4 find the word inside a longer name (*Zumo de naranja*).
 */
internal const val HEAD_MATCH_TIER = 2

/** Stable promotion of foods the user already knows, without changing query relevance within groups. */
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