package com.lethio.macros.domain.model

/** A food from either database, bundled or user-created; nothing above the data layer cares which. */
data class Food(
    val ref: FoodRef,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    /** Composition per 100 native units ([nutritionBasis]), despite the name. */
    val per100g: Macros,
    val servings: List<Serving> = emptyList(),
    /** Required only when crossing mass/volume bases; null never implies water density. */
    val densityGPerMl: Double? = null,
    /** The dataset that supplied the numbers, shown on results and needed for attribution. */
    val provenance: DataSource? = null,
    /** 0-100 completeness and confidence from the pipeline; ranks search results. */
    val qualityScore: Int? = null,
    /** Set when [per100g]'s carbohydrate is not plain available carbohydrate; see [CarbLabel]. */
    val carbLabel: CarbLabel? = null,
    val nutritionBasis: NutritionBasis = NutritionBasis.MASS,
) {
    /** Both preview and save scale from the explicitly resolved native denominator. */
    fun nutritionFor(quantity: Quantity): Macros? {
        if (quantity.basis != nutritionBasis || !quantity.basisAmount.isFinite() || quantity.basisAmount < 0) return null
        return runCatching { per100g * (quantity.basisAmount / 100.0) }.getOrNull()
            ?.takeIf { listOf(it.calories, it.proteinG, it.fatG, it.carbsG).all { n -> n.isFinite() && n >= 0 } }
    }

    val nativeUnit: MeasureUnit get() = if (nutritionBasis == NutritionBasis.VOLUME)
        MeasureUnit.Milliliters else MeasureUnit.Grams

    val defaultServing: Serving?
        get() = servings.firstOrNull { it.isDefault } ?: servings.firstOrNull()

    /**
     * Native units plus conversions a finite positive density allows. Deduplicated by label, since
     * source portion tables can repeat a built-in unit ("oz").
     */
    val availableUnits: List<MeasureUnit>
        get() = buildList {
            val densityValid = densityGPerMl?.let { it.isFinite() && it > 0 } == true
            if (nutritionBasis == NutritionBasis.MASS || densityValid) {
                servings.forEach { add(MeasureUnit.Portion(it.label, it.grams)) }
                add(MeasureUnit.Grams)
                add(MeasureUnit.Ounces)
            }
            if (nutritionBasis == NutritionBasis.VOLUME || densityValid) add(MeasureUnit.Milliliters)
        }.distinctBy { it.label.trim().lowercase() }
}

/** Why a carbohydrate figure is not plain available carbohydrate. */
enum class CarbLabel {
    /** Source is by-difference (fibre included) and this row has no fibre figure to subtract. */
    INCLUDES_FIBRE,

    /** CoFID only: available carbohydrate as monosaccharide equivalents, a different scale. */
    MONOSACCHARIDE_EQUIVALENT,
}
