package com.lethio.macros.data.off

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.DataSource
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The gate between a crowd-sourced database and someone's food diary.
 *
 * Open Food Facts loses about 55% of its rows to these checks in the build pipeline, and the same
 * has to be true of a row that arrives over the network — otherwise the app's whole quality claim
 * holds only for foods that happened to ship in the APK.
 *
 * Payloads here are shaped like real v3 responses, verified against production on 2026-09-01.
 */
class OffProductMapperTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun parse(body: String) = json.decodeFromString<OffResponse>(body)

    private fun response(
        productName: String = "Test Product",
        brands: String? = null,
        kcal: Double? = 539.0,
        protein: Double? = 6.3,
        fat: Double? = 30.9,
        carbs: Double? = 57.5,
        servingSize: String? = null,
        servingQuantity: Double? = null,
        errorTags: String = "[]",
        resultId: String = "product_found",
    ) =
        parse(
            """
        {
          "code": "3017620422003",
          "status": "success",
          "result": { "id": "$resultId" },
          "errors": [], "warnings": [],
          "product": {
            "product_name": "$productName",
            ${brands?.let { """"brands": "$it",""" } ?: ""}
            ${servingSize?.let { """"serving_size": "$it",""" } ?: ""}
            ${servingQuantity?.let { """"serving_quantity": $it,""" } ?: ""}
            "data_quality_errors_tags": $errorTags,
            "nutriments": {
              ${kcal?.let { """"energy-kcal_100g": $it,""" } ?: ""}
              ${protein?.let { """"proteins_100g": $it,""" } ?: ""}
              ${fat?.let { """"fat_100g": $it,""" } ?: ""}
              ${carbs?.let { """"carbohydrates_100g": $it""" } ?: """"dummy": 0"""}
            }
          }
        }
        """
                .trimIndent()
        )

    @Test
    fun `maps a well-formed product`() {
        val food = OffProductMapper.toFood(response(), "3017620422003")

        assertThat(food).isNotNull()
        assertThat(food!!.name).isEqualTo("Test Product")
        assertThat(food.per100g.calories).isEqualTo(539.0)
        assertThat(food.per100g.proteinG).isEqualTo(6.3)
        assertThat(food.barcode).isEqualTo("3017620422003")
        // Provenance is not decoration: ODbL attribution and the contribution feature that comes
        // later both depend on this row being distinguishable from one the user typed.
        assertThat(food.provenance).isEqualTo(DataSource.OPEN_FOOD_FACTS)
    }

    @Test
    fun `rejects kilojoules recorded as kilocalories`() {
        // Nutella's real figures, with the kJ value in the kcal field -- the single most common
        // corruption in this dataset, and invisible to every bounds check.
        val food =
            OffProductMapper.toFood(
                response(kcal = 2252.0, protein = 6.3, fat = 30.9, carbs = 57.5),
                "3017620422003",
            )
        assertThat(food).isNull()
    }

    @Test
    fun `rejects macros scaled by one hundred`() {
        val food =
            OffProductMapper.toFood(
                response(kcal = 539.0, protein = 630.0, fat = 3090.0, carbs = 5750.0),
                "3017620422003",
            )
        assertThat(food).isNull()
    }

    @Test
    fun `rejects a row Open Food Facts has itself flagged`() {
        val food =
            OffProductMapper.toFood(
                response(errorTags = """["en:energy-value-in-kcal-greater-than-computed-value"]"""),
                "3017620422003",
            )
        assertThat(food).isNull()
    }

    @Test
    fun `rejects a product with no energy figure`() {
        // 43% of Open Food Facts rows have no energy at all. Not an error, just unusable.
        assertThat(OffProductMapper.toFood(response(kcal = null), "3017620422003")).isNull()
    }

    @Test
    fun `rejects when the response did not find a product`() {
        assertThat(
                OffProductMapper.toFood(response(resultId = "product_not_found"), "3017620422003")
            )
            .isNull()
    }

    @Test
    fun `keeps a multi-brand string whole rather than splitting it`() {
        // Matches build_nutrition_db.py exactly. Splitting reads better but would make the same
        // product display differently depending on whether it arrived over the network or in the
        // seed, which is worse than reading badly.
        val food =
            OffProductMapper.toFood(response(brands = "Nutella, Ferrero, Yum yum"), "3017620422003")
        assertThat(food?.brand).isEqualTo("Nutella, Ferrero, Yum yum")
    }

    @Test
    fun `drops a brand that merely repeats the name`() {
        // 3,053 products are named after their own brand; showing both renders "Pepsi - Pepsi".
        val food =
            OffProductMapper.toFood(
                response(productName = "Pepsi", brands = "pepsi"),
                "3017620422003",
            )
        assertThat(food?.brand).isNull()
    }

    @Test
    fun `reads a mass serving and drops a volume one`() {
        val mass = OffProductMapper.toFood(response(servingSize = "30 g"), "3017620422003")
        assertThat(mass?.servings?.single()?.grams).isEqualTo(30.0)

        // No source here carries a density, so millilitres cannot be converted. A missing serving
        // costs one extra tap; a wrong one gets logged as fact.
        val volume =
            OffProductMapper.toFood(
                response(servingSize = "250 ml", servingQuantity = 250.0),
                "3017620422003",
            )
        assertThat(volume?.servings).isEmpty()
    }

    @Test
    fun `rejects an implausible barcode even when the product looks fine`() {
        assertThat(OffProductMapper.toFood(response(), "12x4")).isNull()
    }

    @Test
    fun `zero energy accepts water and trace macros but rejects protein or fat above trace`() {
        for ((protein, fat, carbs) in
            listOf(Triple(0.0, 0.0, 0.0), Triple(0.5, 0.5, 0.0), Triple(0.0, 0.0, 5.0))) {
            assertThat(
                    OffProductMapper.toFood(
                        response(kcal = 0.0, protein = protein, fat = fat, carbs = carbs),
                        "3017620422003",
                    )
                )
                .isNotNull()
        }
        for ((protein, fat, carbs) in
            listOf(Triple(0.501, 0.0, 0.0), Triple(0.0, 0.501, 0.0), Triple(0.0, 0.0, 100.0))) {
            assertThat(
                    OffProductMapper.toFood(
                        response(kcal = 0.0, protein = protein, fat = fat, carbs = carbs),
                        "3017620422003",
                    )
                )
                .isNull()
        }
    }

    @Test
    fun `missing macros are never inferred as zero`() {
        for (payload in
            listOf(
                response(kcal = null),
                response(protein = null),
                response(fat = null),
                response(carbs = null),
            )) {
            assertThat(OffProductMapper.toFood(payload, "3017620422003")).isNull()
        }
    }
}
