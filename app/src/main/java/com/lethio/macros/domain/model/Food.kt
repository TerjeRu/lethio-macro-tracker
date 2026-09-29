package com.lethio.macros.domain.model

data class Food(
    val ref: FoodRef,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,

    val per100g: Macros,
    val servings: List<Serving> = emptyList(),

    val densityGPerMl: Double? = null,

    val provenance: DataSource? = null,

    val qualityScore: Int? = null,

    val carbLabel: CarbLabel? = null,
) {
    val defaultServing: Serving?
        get() = servings.firstOrNull { it.isDefault } ?: servings.firstOrNull()

    val availableUnits: List<MeasureUnit>
        get() = buildList {
            servings.forEach { add(MeasureUnit.Portion(it.label, it.grams)) }
            add(MeasureUnit.Grams)
            add(MeasureUnit.Ounces)
            if (densityGPerMl != null) add(MeasureUnit.Milliliters)
        }.distinctBy { it.label.trim().lowercase() }
}

enum class CarbLabel {

    INCLUDES_FIBRE,

    MONOSACCHARIDE_EQUIVALENT,
}
