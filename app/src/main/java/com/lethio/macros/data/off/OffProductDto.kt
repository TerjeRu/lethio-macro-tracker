package com.lethio.macros.data.off

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The part of Open Food Facts' v3 product response this app requests. Every field is optional: the
 * data is crowd-sourced, and a missing field is ordinary.
 */
@Serializable
data class OffResponse(
    val code: String? = null,
    val product: OffProduct? = null,
    /** v3's outcome; `result.id` is `"product_found"` on success. [status] says `"success"` regardless. */
    val result: OffResult? = null,
    val status: String? = null,
    val errors: List<OffMessage> = emptyList(),
    val warnings: List<OffMessage> = emptyList(),
)

@Serializable
data class OffResult(val id: String? = null, val name: String? = null)

/** Errors and warnings carry more than this; only the id is ever logged, and never the payload. */
@Serializable
data class OffMessage(val id: String? = null)

@Serializable
data class OffProduct(
    @SerialName("product_name") val productName: String? = null,
    /** A comma-separated list ("Nutella, Ferrero, Yum yum"), kept whole; see `OffProductMapper`. */
    val brands: String? = null,
    val quantity: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    /** A bare number with no unit; see `OffProductMapper.servingGrams`. */
    @SerialName("serving_quantity") val servingQuantity: Double? = null,
    /** Open Food Facts' own data-quality failures, which the pipeline and the app both reject on. */
    @SerialName("data_quality_errors_tags") val dataQualityErrorsTags: List<String> = emptyList(),
    val nutriments: OffNutriments? = null,
)

@Serializable
data class OffNutriments(
    /**
     * Kilocalories per 100 g. The sibling `energy_100g` is kilojoules and is not mapped;
     * reading it as kcal is this dataset's most common corruption.
     */
    @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("proteins_100g") val proteins100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("carbohydrates_100g") val carbohydrates100g: Double? = null,
)
