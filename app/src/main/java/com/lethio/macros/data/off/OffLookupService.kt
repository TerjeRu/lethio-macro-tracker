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

/** What a lookup can come back as. Only [Found] carries anything the user can act on. */
sealed interface OffLookupResult {
    data class Found(val food: Food) : OffLookupResult

    /** OFF explicitly reports that this barcode is absent; the only contribution-eligible result. */
    data object Missing : OffLookupResult

    /** OFF has the product, but its data does not pass the mapper. */
    data object NotUsable : OffLookupResult

    /** Rejected locally, without asking OFF. Never evidence of absence. */
    data object InvalidBarcode : OffLookupResult

    /** Offline, timed out, refused, or unparseable. Never surfaced as an error dialog. */
    data object Unavailable : OffLookupResult
}

/**
 * Reads one product from Open Food Facts, only when the setting is on and the reader taps the
 * button for a barcode. Sends the barcode and the User-Agent, nothing that identifies the install.
 *
 * Production is `.org` (`.net` is staging, despite OFF's tutorial). API v3 for its machine-readable
 * result. The read limit is 15 requests per minute per IP, so a single user-initiated lookup needs
 * no limiter, retries or prefetching.
 */
@Singleton
class OffLookupService @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun lookup(barcode: String): OffLookupResult = withContext(Dispatchers.IO) {
        // Validate before the request: the barcode goes into the URL, and only a barcode may leave.
        if (!NutritionPlausibility.isBarcodeUsable(barcode)) {
            return@withContext OffLookupResult.InvalidBarcode
        }
        val normalizedBarcode = barcode.trim()

        try {
            // Inside the try, so a parsing exception cannot escape the catch arms.
            val request = Request.Builder()
                .url("$BASE_URL/api/v3/product/$normalizedBarcode.json?fields=$FIELDS")
                .header("User-Agent", OffUserAgent.VALUE)
                .build()
            client.newCall(request).execute().use { response ->
                // OFF answers an unknown barcode with 404: missing, not unavailable.
                if (response.code == HTTP_NOT_FOUND) return@withContext OffLookupResult.Missing
                if (!response.isSuccessful) return@withContext OffLookupResult.Unavailable
                // Bounded: OutOfMemoryError would pass both catch arms.
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
            // Offline and timed out are the expected cases, not exceptional ones.
            OffLookupResult.Unavailable
        } catch (e: Exception) {
            // A malformed or restructured payload must not take the scanner down with it.
            OffLookupResult.Unavailable
        }
    }

    private companion object {
        const val BASE_URL = "https://world.openfoodfacts.org"
        const val HTTP_NOT_FOUND = 404

        /** Exactly what the mapper reads, and nothing else — the response is large by default. */
        const val FIELDS =
            "code,product_name,brands,quantity,serving_size,serving_quantity," +
                "nutriments,data_quality_errors_tags"

    }
}
