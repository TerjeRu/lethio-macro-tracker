package com.lethio.macros.data.off

import com.lethio.macros.BuildConfig
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.nutrition.NutritionPlausibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OffContributeResult {
    data object Accepted : OffContributeResult
    data object Refused : OffContributeResult
    data object Unavailable : OffContributeResult
    data object RateLimited : OffContributeResult
}

@Singleton
class OffContributeService @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var relayUrl = BuildConfig.OFF_RELAY_URL

    internal constructor(client: OkHttpClient, json: Json, relayUrl: String) : this(client, json) {
        this.relayUrl = relayUrl
    }

    val isConfigured: Boolean
        get() = relayUrl.isNotBlank()

    internal val redirectsEnabled: Boolean
        get() = client.followRedirects || client.followSslRedirects

    suspend fun contribute(food: Food, appUuid: String): OffContributeResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext OffContributeResult.Refused

            val barcode = food.barcode?.trim().orEmpty()
            if (!NutritionPlausibility.isBarcodeUsable(barcode)) {
                return@withContext OffContributeResult.Refused
            }
            if (!isRelayTextUsable(food.name, minimumLength = 2)) {
                return@withContext OffContributeResult.Refused
            }
            val brand = food.brand?.trimForRelay().orEmpty()
            if (brand.isNotEmpty() && !isRelayTextUsable(brand, minimumLength = 1)) {
                return@withContext OffContributeResult.Refused
            }
            if (!isRelayNutritionUsable(food.per100g)) {
                return@withContext OffContributeResult.Refused
            }

            val request = Request.Builder()
                .url(relayUrl)
                .header("User-Agent", OffUserAgent.VALUE)
                .post(encodedBody(food, barcode, appUuid).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        429 -> OffContributeResult.RateLimited
                        400, 413, 415, 422 -> OffContributeResult.Refused
                        503 -> OffContributeResult.Unavailable
                        200 -> response.acceptedResult()
                        else -> OffContributeResult.Unavailable
                    }
                }
            } catch (e: IOException) {
                OffContributeResult.Unavailable
            } catch (e: Exception) {
                OffContributeResult.Unavailable
            }
        }

    internal fun requestBody(food: Food, barcode: String, appUuid: String): RelayContributionRequest =
        RelayContributionRequest(
            protocolVersion = PROTOCOL_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            appUuid = hashAppUuid(appUuid),
            barcode = barcode,
            productName = food.name.trimForRelay(),
            brand = food.brand?.trimForRelay()?.takeIf(String::isNotEmpty),
            energyKcal100g = food.per100g.calories,
            proteinG100g = food.per100g.proteinG,
            fatG100g = food.per100g.fatG,
            carbohydrateG100g = food.per100g.carbsG,
        )

    internal fun encodedBody(food: Food, barcode: String, appUuid: String): String =
        json.encodeToString(requestBody(food, barcode, appUuid))

    private fun okhttp3.Response.acceptedResult(): OffContributeResult {
        val responseBody = bodyWithCeiling() ?: return OffContributeResult.Unavailable
        val parsed = json.decodeFromString<RelayContributionResponse>(responseBody)
        return if (parsed.status == "accepted") {
            OffContributeResult.Accepted
        } else {
            OffContributeResult.Unavailable
        }
    }

    private fun hashAppUuid(appUuid: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$UUID_SALT$appUuid".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val UUID_SALT = "lethio-macros-off-contribution-v1:"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
internal data class RelayContributionRequest(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_uuid") val appUuid: String,
    val barcode: String,
    @SerialName("product_name") val productName: String,
    val brand: String? = null,
    @SerialName("energy_kcal_100g") val energyKcal100g: Double,
    @SerialName("protein_g_100g") val proteinG100g: Double,
    @SerialName("fat_g_100g") val fatG100g: Double,
    @SerialName("carbohydrate_g_100g") val carbohydrateG100g: Double,
)

@Serializable
private data class RelayContributionResponse(val status: String? = null)
