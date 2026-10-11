package com.lethio.macros.data.off

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * What the app is willing to put in a URL and send to a third party: the barcode and the
 * User-Agent, nothing else. The mapper would refuse a bad result anyway, so these count requests
 * rather than assert results.
 */
class OffLookupServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Counts what reached the wire, and answers 404 so nothing touches the network. */
    private class CountingInterceptor : Interceptor {
        val calls = AtomicInteger(0)
        val urls = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            calls.incrementAndGet()
            urls += chain.request().url.toString()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody(null))
                .build()
        }
    }

    private fun serviceWith(interceptor: CountingInterceptor) =
        OffLookupService(OkHttpClient.Builder().addInterceptor(interceptor).build(), json)

    @Test
    fun `a non-barcode is refused without any request being made`() {
        // The assertion that fails if the guard is moved back below the call: the result would
        // still be NotUsable, because the mapper refuses it too, but the request would have gone.
        val interceptor = CountingInterceptor()
        val service = serviceWith(interceptor)

        val inputs =
            listOf(
                "../../../../cgi/nutrient_levels",
                "5012345678900#truncated",
                "5012345678900?fields=all",
                "https://example.invalid/",
                "٠١٢٣٤٥٦٧",
                "",
                "a".repeat(4000),
            )

        inputs.forEach { barcode ->
            val result = runBlocking { service.lookup(barcode) }
            assertThat(result).isEqualTo(OffLookupResult.InvalidBarcode)
        }

        assertThat(interceptor.calls.get()).isEqualTo(0)
        assertThat(interceptor.urls).isEmpty()
    }

    @Test
    fun `a real barcode still reaches the network, and reaches it intact`() {
        // The other half. A guard that refused everything would pass the test above.
        val interceptor = CountingInterceptor()
        val service = serviceWith(interceptor)

        val result = runBlocking { service.lookup("5012345678900") }

        // A 404 explicitly confirms absence.
        assertThat(result).isEqualTo(OffLookupResult.Missing)
        assertThat(interceptor.calls.get()).isEqualTo(1)

        val url = interceptor.urls.single()
        assertThat(url)
            .startsWith("https://world.openfoodfacts.org/api/v3/product/5012345678900.json")
        // Nothing but the barcode and the field list; no account, no identifier, no scan history.
        assertThat(url).doesNotContain("app_uuid")
        assertThat(url).doesNotContain("user_id")
    }

    @Test
    fun `a whitespace-padded barcode is accepted and sent`() {
        // isBarcodeUsable trims, so the guard must not reject what the rest of the app accepts.
        val interceptor = CountingInterceptor()
        val service = serviceWith(interceptor)

        runBlocking { service.lookup("  5012345678900  ") }

        assertThat(interceptor.calls.get()).isEqualTo(1)
        assertThat(interceptor.urls.single()).doesNotContain("%20")
    }

    @Test
    fun `response outcomes distinguish absence from rejected or malformed data`() = runBlocking {
        val cases =
            listOf(
                """{"result":{"id":"product_not_found"}}""" to OffLookupResult.Missing,
                """{"result":{"id":"product_found"},"product":{"product_name":"Water","nutriments":{"energy-kcal_100g":0}}}""" to
                    OffLookupResult.NotUsable,
                """{"result":{"id":"product_found"},"product":{"product_name":"Water","data_quality_errors_tags":["en:energy-value-error"],"nutriments":{"energy-kcal_100g":0,"proteins_100g":0,"fat_100g":0,"carbohydrates_100g":0}}}""" to
                    OffLookupResult.NotUsable,
                "{}" to OffLookupResult.Unavailable,
                "not json" to OffLookupResult.Unavailable,
                """{"result":{"id":"unexpected"}}""" to OffLookupResult.Unavailable,
            )
        for ((body, expected) in cases) {
            val service =
                OffLookupService(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body.toResponseBody())
                                .build()
                        }
                        .build(),
                    json,
                )
            assertThat(service.lookup("5012345678900")).isEqualTo(expected)
        }
    }

    @Test
    fun `timeout remains unavailable rather than missing`() = runBlocking {
        val service =
            OffLookupService(
                OkHttpClient.Builder()
                    .addInterceptor { throw java.net.SocketTimeoutException("test timeout") }
                    .build(),
                json,
            )
        assertThat(service.lookup("5012345678900")).isEqualTo(OffLookupResult.Unavailable)
    }
}
