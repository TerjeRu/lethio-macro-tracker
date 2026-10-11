package com.lethio.macros.data.off

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.domain.model.NutrientQualifier
import com.lethio.macros.domain.model.NutrientValue
import com.lethio.macros.domain.model.NutritionBasis
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test

class OffContributeServiceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val rawUuid = "b9f3c1e2-7a4d-4c8b-9e11-2f6a5d8c3b07"

    private fun food(
        name: String = "Test Product",
        brand: String? = "TestBrand",
        barcode: String? = "3017620422003",
        kcal: Double = 539.0,
        protein: Double = 6.3,
        fat: Double = 30.9,
        carbs: Double = 57.5,
    ) =
        Food(
            ref = FoodRef.QuickAdd,
            name = name,
            brand = brand,
            barcode = barcode,
            per100g = Macros(calories = kcal, proteinG = protein, fatG = fat, carbsG = carbs),
        )

    private fun service(
        interceptor: RecordingInterceptor = RecordingInterceptor(),
        url: String = "https://relay.example/v1/off/contributions",
    ) = OffContributeService(OkHttpClient.Builder().addInterceptor(interceptor).build(), json, url)

    @Test
    fun `request is the exact closed relay JSON shape with no reusable credential`() {
        val service = service()
        val encoded =
            service.encodedBody(food(name = "Tinto de Verano Limón"), "8410283381758", rawUuid)
        val body = json.parseToJsonElement(encoded).jsonObject

        assertThat(body.keys)
            .containsExactly(
                "protocol_version",
                "app_version",
                "app_uuid",
                "barcode",
                "product_name",
                "brand",
                "energy_kcal_100g",
                "protein_g_100g",
                "fat_g_100g",
                "carbohydrate_g_100g",
            )
        assertThat(body["protocol_version"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(body["barcode"]?.jsonPrimitive?.content).isEqualTo("8410283381758")
        assertThat(body["product_name"]?.jsonPrimitive?.content).isEqualTo("Tinto de Verano Limón")
        assertThat(body.keys).doesNotContain("user_id")
        assertThat(body.keys).doesNotContain("password")
    }

    @Test
    fun `raw UUID stays local while the existing stable hash is preserved`() {
        val service = service()
        val first = service.requestBody(food(), "3017620422003", rawUuid)
        val same = service.requestBody(food(), "3017620422003", rawUuid)
        val other =
            service.requestBody(food(), "3017620422003", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")

        assertThat(first.appUuid).hasLength(32)
        assertThat(first.appUuid).matches("[0-9a-f]{32}")
        assertThat(first.appUuid).isEqualTo(same.appUuid)
        assertThat(first.appUuid).isNotEqualTo(other.appUuid)
        val encoded = service.encodedBody(food(), "3017620422003", rawUuid)
        assertThat(encoded).doesNotContain(rawUuid)
        assertThat(encoded).contains("\"app_uuid\":\"${first.appUuid}\"")
    }

    @Test
    fun `real call sends UTF-8 JSON only to the configured fixed URL`() = runBlocking {
        val interceptor = RecordingInterceptor()
        val service = service(interceptor)

        val result = service.contribute(food(name = "Käse Smørbrød Açúcar"), rawUuid)

        assertThat(result).isEqualTo(OffContributeResult.Accepted)
        val request = interceptor.requests.single()
        assertThat(request.url.toString()).isEqualTo("https://relay.example/v1/off/contributions")
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body?.contentType().toString())
            .isEqualTo("application/json; charset=utf-8")
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val sent = buffer.readUtf8()
        assertThat(sent).contains("Käse Smørbrød Açúcar")
        assertThat(sent).doesNotContain("password")
        assertThat(sent).doesNotContain(rawUuid)
    }

    @Test
    fun `supplementary symbol at the code-point length ceiling is accepted`() = runBlocking {
        val interceptor = RecordingInterceptor()
        val name = "x".repeat(119) + "🫖"

        assertThat(service(interceptor).contribute(food(name = name), rawUuid))
            .isEqualTo(OffContributeResult.Accepted)
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `HTTP and machine states map without exposing relay bodies`() {
        val cases =
            listOf(
                Triple(200, """{"status":"accepted"}""", OffContributeResult.Accepted),
                Triple(400, "private validation detail", OffContributeResult.Refused),
                Triple(413, "too large", OffContributeResult.Refused),
                Triple(415, "wrong media", OffContributeResult.Refused),
                Triple(422, "private OFF refusal", OffContributeResult.Refused),
                Triple(429, "nginx body is irrelevant", OffContributeResult.RateLimited),
                Triple(503, "private outage", OffContributeResult.Unavailable),
                Triple(404, "wrong deployment", OffContributeResult.Unavailable),
                Triple(200, """{"status":"refused"}""", OffContributeResult.Unavailable),
                Triple(200, "not json", OffContributeResult.Unavailable),
            )
        cases.forEach { (code, body, expected) ->
            val result = runBlocking {
                service(RecordingInterceptor(code, body)).contribute(food(), rawUuid)
            }
            assertThat(result).isEqualTo(expected)
        }
    }

    @Test
    fun `invalid products and unconfigured builds never make a request`() = runBlocking {
        val interceptor = RecordingInterceptor()
        assertThat(service(interceptor).contribute(food(barcode = "3017620422004"), rawUuid))
            .isEqualTo(OffContributeResult.Refused)
        assertThat(service(interceptor, url = "").contribute(food(), rawUuid))
            .isEqualTo(OffContributeResult.Refused)
        assertThat(interceptor.requests).isEmpty()
    }

    @Test
    fun `unsupported nutrition cannot be sent as plain per 100 g figures`() = runBlocking {
        val interceptor = RecordingInterceptor()
        val relay = service(interceptor)
        val ordinary = food()
        val unsupported =
            listOf(
                ordinary.copy(nutritionBasis = NutritionBasis.VOLUME, densityGPerMl = 0.8),
                ordinary.copy(
                    per100g =
                        ordinary.per100g.withNutrients(
                            mapOf(
                                Nutrient.FAT to NutrientValue(30.9, setOf(NutrientQualifier.TRACE))
                            )
                        )
                ),
                ordinary.copy(
                    per100g =
                        ordinary.per100g.withNutrients(mapOf(Nutrient.PROTEIN to NutrientValue()))
                ),
            )
        for (candidate in unsupported) {
            assertThat(relay.contribute(candidate, rawUuid)).isEqualTo(OffContributeResult.Refused)
        }
        assertThat(interceptor.requests).isEmpty()
        // A normal typed mass product still uses the existing closed request successfully.
        assertThat(relay.contribute(ordinary, rawUuid)).isEqualTo(OffContributeResult.Accepted)
        assertThat(interceptor.requests).hasSize(1)
    }

    @Test
    fun `shared contribution fixture drives real preflight without invalid requests`() {
        val fixtureBytes =
            checkNotNull(
                    javaClass.classLoader?.getResourceAsStream("contribution-validation-v1.json")
                )
                .use { it.readBytes() }
        val fixtureHash =
            MessageDigest.getInstance("SHA-256").digest(fixtureBytes).joinToString("") {
                "%02x".format(it)
            }
        assertThat(fixtureHash)
            .isEqualTo("5f0b1bd9bb11fbb31403d72f4903109526609348b745d94b2709564ba7f2f731")
        val fixture = json.parseToJsonElement(fixtureBytes.decodeToString()).jsonObject
        assertThat(fixture["schema_version"]?.jsonPrimitive?.content).isEqualTo("1")

        fixture.getValue("text_cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val field = case.getValue("field").jsonPrimitive.content
            val value = case.getValue("value").jsonPrimitive.content
            val accepted = case.getValue("accepted").jsonPrimitive.boolean
            val interceptor = RecordingInterceptor()
            val candidate =
                when (field) {
                    "product_name" -> food(name = value)
                    "brand" -> food(brand = value)
                    else -> error("Unknown fixture field in $id")
                }

            val result = runBlocking { service(interceptor).contribute(candidate, rawUuid) }

            assertThat(result == OffContributeResult.Accepted).isEqualTo(accepted)
            assertThat(interceptor.requests.size).isEqualTo(if (accepted) 1 else 0)
        }

        fixture.getValue("nutrition_cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val accepted = case.getValue("accepted").jsonPrimitive.boolean
            val interceptor = RecordingInterceptor()
            val candidate =
                food(
                    kcal = case.getValue("energy_kcal_100g").jsonPrimitive.double,
                    protein = case.getValue("protein_g_100g").jsonPrimitive.double,
                    fat = case.getValue("fat_g_100g").jsonPrimitive.double,
                    carbs = case.getValue("carbohydrate_g_100g").jsonPrimitive.double,
                )

            val result = runBlocking { service(interceptor).contribute(candidate, rawUuid) }

            assertThat(result == OffContributeResult.Accepted).isEqualTo(accepted)
            assertThat(interceptor.requests.size).isEqualTo(if (accepted) 1 else 0)
        }
    }

    @Test
    fun `contribution client cannot follow relay redirects`() {
        assertThat(service().redirectsEnabled).isFalse()
    }
}

private class RecordingInterceptor(
    private val code: Int = 200,
    private val responseBody: String = """{"status":"accepted"}""",
) : Interceptor {
    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        requests += chain.request()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
