package com.lethio.macros.data.off

import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.nutrition.NutritionPlausibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OffLookupResult {
    data class Found(val food: Food) : OffLookupResult

    data object Missing : OffLookupResult

    data object NotUsable : OffLookupResult

    data object InvalidBarcode : OffLookupResult

    data object Unavailable : OffLookupResult
}

@Singleton
class OffLookupService @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun lookup(barcode: String): OffLookupResult = withContext(Dispatchers.IO) {

        if (!NutritionPlausibility.isBarcodeUsable(barcode)) {
            return@withContext OffLookupResult.InvalidBarcode
        }
        val normalizedBarcode = barcode.trim()

        try {

            val request = Request.Builder()
                .url("$BASE_URL/api/v3/product/$normalizedBarcode.json?fields=$FIELDS")
                .header("User-Agent", OffUserAgent.VALUE)
                .build()
            client.newCall(request).execute().use { response ->

                if (response.code == HTTP_NOT_FOUND) return@withContext OffLookupResult.Missing
                if (!response.isSuccessful) return@withContext OffLookupResult.Unavailable

                val body = response.bodyWithCeiling() ?: return@withContext OffLookupResult.Unavailable
                val parsed = json.decodeFromString<OffResponse>(body)
                when (parsed.result?.id) {
                    "product_not_found" -> OffLookupResult.Missing
                    "product_found" -> OffProductMapper.toFood(parsed, normalizedBarcode)
                        ?.let(OffLookupResult::Found) ?: OffLookupResult.NotUsable
                    else -> OffLookupResult.Unavailable
                }
            }
        } catch (e: IOException) {

            OffLookupResult.Unavailable
        } catch (e: Exception) {

            OffLookupResult.Unavailable
        }
    }

    private companion object {
        const val BASE_URL = "https://world.openfoodfacts.org"
        const val HTTP_NOT_FOUND = 404

        const val FIELDS =
            "code,product_name,brands,quantity,serving_size,serving_quantity," +
                "nutriments,data_quality_errors_tags"

    }
}
