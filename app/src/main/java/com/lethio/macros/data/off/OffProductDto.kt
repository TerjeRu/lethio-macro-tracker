package com.lethio.macros.data.off

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OffResponse(
    val code: String? = null,
    val product: OffProduct? = null,

    val result: OffResult? = null,
    val status: String? = null,
    val errors: List<OffMessage> = emptyList(),
    val warnings: List<OffMessage> = emptyList(),
)

@Serializable
data class OffResult(val id: String? = null, val name: String? = null)

@Serializable
data class OffMessage(val id: String? = null)

@Serializable
data class OffProduct(
    @SerialName("product_name") val productName: String? = null,

    val brands: String? = null,
    val quantity: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,

    @SerialName("serving_quantity") val servingQuantity: Double? = null,

    @SerialName("data_quality_errors_tags") val dataQualityErrorsTags: List<String> = emptyList(),
    val nutriments: OffNutriments? = null,
)

@Serializable
data class OffNutriments(

    @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("proteins_100g") val proteins100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("carbohydrates_100g") val carbohydrates100g: Double? = null,
)
