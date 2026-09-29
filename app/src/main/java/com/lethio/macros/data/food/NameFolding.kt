package com.lethio.macros.data.food

import java.text.Normalizer

// Must match fold_name in tools/build_nutrition_db.py so queries match the generated index.
fun foldForSearch(input: String): String {
    val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)

    val stripped = buildString(decomposed.length) {
        for (char in decomposed) {

            if (char.category != CharCategory.NON_SPACING_MARK) append(char)
        }
    }

    val lowered = stripped.lowercase()

    return buildString(lowered.length) {
        for (char in lowered) append(LIGATURES[char] ?: char.toString())
    }
}

private val LIGATURES = mapOf(

    'œ' to "oe",
    'æ' to "ae",
    'ß' to "ss",
    'ĳ' to "ij",

    'ø' to "o",
    'ł' to "l",
    'ð' to "d",
)
