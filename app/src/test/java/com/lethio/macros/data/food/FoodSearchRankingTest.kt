package com.lethio.macros.data.food

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Macros
import org.junit.Test

class FoodSearchRankingTest {

    @Test
    fun `composition keeps generic shelves before branded and removes duplicate refs`() {
        val primary = food(1, "primary")
        val fallback = food(2, "fallback")
        val branded = food(3, "branded")

        assertThat(
                composeSearchResults(
                    primary = listOf(primary),
                    fallback = listOf(fallback, primary),
                    branded = listOf(branded, fallback),
                    limit = 10,
                    keyOf = { it.ref },
                )
            )
            .containsExactly(primary, fallback, branded)
            .inOrder()
    }

    @Test
    fun `a better tier from the fallback shelf outranks a worse one from the national table`() {
        // The defect this replaced: BEDCA has no raw orange, so a Spanish reader typing "naranja"
        // got Zumo de naranja ahead of a plain Naranja from another source, purely because the
        // national shelf was concatenated first. Tier 3 is "the word appears somewhere inside a
        // longer name", which is not the national table having answered.
        val juice = food(1, "Zumo de naranja")
        val orange = food(2, "Naranja")

        assertThat(
                composeSearchResults(
                    primary = listOf(juice),
                    fallback = listOf(orange),
                    branded = emptyList(),
                    limit = 10,
                    keyOf = { it.ref },
                    tierOf = { if (it == juice) 3 else 0 },
                )
            )
            .containsExactly(orange, juice)
            .inOrder()
    }

    @Test
    fun `the national table keeps a head match even when the fallback matches better`() {
        // Why the raw tier was rejected: Sweden writes "Bröd vitt glutenfritt" (tier 2, no comma)
        // and Norway writes "Brød, grovt, glutenfritt" (tier 1), so ordering on the tier alone
        // hands a Swedish reader Norwegian bread on a punctuation habit. Both are head matches, so
        // the reader's own table answers.
        val ownTable = food(1, "Bröd vitt glutenfritt")
        val betterTierElsewhere = food(2, "Brød, grovt, glutenfritt")

        assertThat(
                composeSearchResults(
                    primary = listOf(ownTable),
                    fallback = listOf(betterTierElsewhere),
                    branded = emptyList(),
                    limit = 10,
                    keyOf = { it.ref },
                    tierOf = { if (it == ownTable) 2 else 1 },
                )
            )
            .containsExactly(ownTable, betterTierElsewhere)
            .inOrder()
    }

    @Test
    fun `the national table still wins a tie inside one tier`() {
        val national = food(1, "national")
        val other = food(2, "other")

        assertThat(
                composeSearchResults(
                    primary = listOf(national),
                    fallback = listOf(other),
                    branded = emptyList(),
                    limit = 10,
                    keyOf = { it.ref },
                    tierOf = { 2 },
                )
            )
            .containsExactly(national, other)
            .inOrder()
    }

    @Test
    fun `branded stays last even when it would outrank on tier`() {
        val genericPartialMatch = food(1, "generic")
        val brandedExactMatch = food(2, "branded")

        assertThat(
                composeSearchResults(
                    primary = listOf(genericPartialMatch),
                    fallback = emptyList(),
                    branded = listOf(brandedExactMatch),
                    limit = 10,
                    keyOf = { it.ref },
                    tierOf = { if (it == brandedExactMatch) 0 else 4 },
                )
            )
            .containsExactly(genericPartialMatch, brandedExactMatch)
            .inOrder()
    }

    @Test
    fun `merging is stable within a tier and a shelf`() {
        val foods = (1L..4L).map { food(it, it.toString()) }

        assertThat(
                composeSearchResults(
                    primary = foods,
                    fallback = emptyList(),
                    branded = emptyList(),
                    limit = 10,
                    keyOf = { it.ref },
                    tierOf = { 1 },
                )
            )
            .containsExactlyElementsIn(foods)
            .inOrder()
    }

    @Test
    fun `composition enforces final limit and handles negative limits`() {
        val foods = (1L..4L).map { food(it, it.toString()) }

        assertThat(composeSearchResults(foods, emptyList(), emptyList(), 2, keyOf = { it.ref }))
            .containsExactly(foods[0], foods[1])
            .inOrder()
        assertThat(composeSearchResults(foods, emptyList(), emptyList(), -1, keyOf = { it.ref }))
            .isEmpty()
    }

    @Test
    fun `known food boost is favorite then recent and stable within groups`() {
        val first = food(1, "first")
        val favorite = food(2, "favorite")
        val recent = food(3, "recent")
        val favoriteAlsoRecent = food(4, "favorite recent")

        assertThat(
                boostKnownFoods(
                    results = listOf(first, recent, favorite, favoriteAlsoRecent),
                    favoriteRefs = setOf(favorite.ref, favoriteAlsoRecent.ref),
                    recentRefs = setOf(recent.ref, favoriteAlsoRecent.ref),
                )
            )
            .containsExactly(favorite, favoriteAlsoRecent, recent, first)
            .inOrder()
    }

    @Test
    fun `known food boost is a no-op without known refs`() {
        val foods = listOf(food(1, "one"), food(2, "two"))
        assertThat(boostKnownFoods(foods, emptySet(), emptySet())).isEqualTo(foods)
    }

    private fun food(id: Long, name: String) =
        Food(ref = FoodRef.Bundled(id), name = name, per100g = Macros.ZERO)
}
