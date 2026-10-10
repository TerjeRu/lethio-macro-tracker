package com.lethio.macros.data.food

import java.text.Normalizer

/**
 * Case- and accent-insensitive form of a food name, for the `LIKE` tiers. SQLite's `LOWER()` folds
 * ASCII only, so names are folded at build time into `foods.name_folded` and queries are folded
 * here. Must agree exactly with `fold_name()` in `tools/build_nutrition_db.py`; `NameFoldingTest`
 * pins both to the same fixtures.
 *
 * Punctuation is kept: the comma in "Butter, salted" is what separates butter from butter beans.
 * FTS5 does its own diacritic removal; this only has to match its Python twin.
 */
fun foldForSearch(input: String): String {
    val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)

    val stripped = buildString(decomposed.length) {
        for (char in decomposed) {
            // Drop the combining marks NFKD split off: é -> e. Stroked letters do not decompose;
            // LIGATURES folds them.
            if (char.category != CharCategory.NON_SPACING_MARK) append(char)
        }
    }

    // Lowercase after normalising: NFKD can produce uppercase (™ becomes "TM").
    val lowered = stripped.lowercase()

    return buildString(lowered.length) {
        for (char in lowered) append(LIGATURES[char] ?: char.toString())
    }
}

/**
 * Characters NFKD does not split, folded to what a reader types without them: digraphs to two
 * letters (*Œuf* from `oeuf`, *Weißbrot* from `weissbrot`), stroked letters to the base letter
 * (*ø* to `o`, matching *å* to `a`, so `rodbete` finds *rødbete*). Mirrored by `LIGATURES` in
 * `tools/build_nutrition_db.py`; `name-folding-fixtures.tsv` keeps them in step.
 */
private val LIGATURES = mapOf(
    // Digraphs: one character, two letters.
    'œ' to "oe",
    'æ' to "ae",
    'ß' to "ss",
    'ĳ' to "ij",
    // Stroked letters: one character, one letter, a stroke through it.
    'ø' to "o",
    'ł' to "l",
    'ð' to "d",
)
