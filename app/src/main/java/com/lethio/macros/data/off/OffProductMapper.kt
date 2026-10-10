package com.lethio.macros.data.off

import com.lethio.macros.domain.model.DataSource
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Serving
import com.lethio.macros.domain.nutrition.NutritionPlausibility

/**
 * Turns an Open Food Facts response into a [Food], or null, which is the usual outcome. Every rule
 * copies `tools/build_nutrition_db.py`, so a looked-up product means what the bundled one would; a
 * rule that looks wrong is a pipeline issue, not something to fix on one side.
 */
object OffProductMapper {

    /** Open Food Facts' own data-quality failures, matched in `data_quality_errors_tags`. */
    private val ERROR_SUBSTRINGS =
        listOf("energy-value", "value-total-over", "value-over-105", "greater-than")

    private val MASS_SERVING = Regex("""([\d.,]+)\s*(kg|g|gram(?:me)?s?|oz|ounces?)\b""", RegexOption.IGNORE_CASE)
    private val VOLUME_SERVING = Regex("""([\d.,]+)\s*(ml|cl|dl|l|litres?|liters?|fl\.?\s*oz)\b""", RegexOption.IGNORE_CASE)

    private val MASS_FACTORS = mapOf(
        "kg" to 1000.0, "g" to 1.0, "gram" to 1.0, "grams" to 1.0,
        "gramme" to 1.0, "grammes" to 1.0,
        "oz" to 28.349523125, "ounce" to 28.349523125, "ounces" to 28.349523125,
    )

    /** The pipeline's cap. A "serving" heavier than this is a pack size, not a portion. */
    private val SERVING_GRAMS = 0.0..2000.0

    /** Matches the pipeline's `CANONICAL_SERVING`: a token the UI localises, never English prose. */
    private const val CANONICAL_SERVING = "serving"

    private const val BRAND_MAX = 80

    /** @param barcode the scanned code, trusted over the payload's: the scan is what the user holds. */
    fun toFood(response: OffResponse, barcode: String): Food? {
        if (response.result?.id != PRODUCT_FOUND) return null
        val product = response.product ?: return null

        // Open Food Facts' own verdict first.
        if (product.dataQualityErrorsTags.any { tag -> ERROR_SUBSTRINGS.any(tag::contains) }) {
            return null
        }

        val name = product.productName?.trim().orEmpty()
        if (!NutritionPlausibility.isNameUsable(name)) return null
        if (!NutritionPlausibility.isBarcodeUsable(barcode)) return null

        val nutriments = product.nutriments ?: return null
        val macros = Macros(
            calories = nutriments.energyKcal100g ?: return null,
            proteinG = nutriments.proteins100g ?: return null,
            fatG = nutriments.fat100g ?: return null,
            carbsG = nutriments.carbohydrates100g ?: return null,
        )
        if (!NutritionPlausibility.isPlausible(macros)) return null

        return Food(
            ref = FoodRef.QuickAdd,
            name = name,
            brand = brandOf(product.brands, name),
            barcode = barcode.trim(),
            per100g = macros,
            servings = servingsOf(product),
            provenance = DataSource.OPEN_FOOD_FACTS,
        )
    }

    /**
     * The brands field kept whole, as the pipeline does, so a product reads the same either way. Null
     * when it repeats the name ("Pepsi · Pepsi").
     */
    private fun brandOf(brands: String?, name: String): String? {
        val brand = brands?.trim()?.take(BRAND_MAX)?.takeIf(String::isNotEmpty) ?: return null
        return brand.takeIf { !it.equals(name.trim(), ignoreCase = true) }
    }

    private fun servingsOf(product: OffProduct): List<Serving> {
        val grams = servingGrams(product.servingSize, product.servingQuantity) ?: return emptyList()
        if (grams !in SERVING_GRAMS || grams == 0.0) return emptyList()
        return listOf(Serving(label = CANONICAL_SERVING, grams = grams))
    }

    /**
     * Grams for a declared serving, or null for a volume: with no density, a wrong conversion would be
     * logged as fact, while a missing serving costs one tap.
     */
    private fun servingGrams(servingSize: String?, servingQuantity: Double?): Double? {
        val text = servingSize?.trim().orEmpty()
        if (VOLUME_SERVING.containsMatchIn(text)) return null

        MASS_SERVING.find(text)?.let { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
            val factor = MASS_FACTORS[match.groupValues[2].lowercase()]
            if (value != null && factor != null) return value * factor
        }

        // Without unit text, serving_quantity is documented as grams; unrecognised units are not trusted.
        return if (text.isEmpty()) servingQuantity else null
    }

    private const val PRODUCT_FOUND = "product_found"
}
