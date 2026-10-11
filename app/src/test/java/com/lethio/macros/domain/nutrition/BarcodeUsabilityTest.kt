package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `isBarcodeUsable` is the app's definition of a barcode and gates every path that reads or writes
 * one — `OffLookupService` before the request, `OffProductMapper` on the response,
 * `OffContributeService` before a write, and `AddProductViewModel` on the form. This tests the rule
 * itself; `OffContributeServiceTest` covers the gates around it.
 */
class BarcodeUsabilityTest {

    @Test
    fun `real retail barcodes are usable at every length the standards define`() {
        listOf(
                "96385074", // EAN-8
                "036000291452", // UPC-A, 12
                "4006381333931", // EAN-13
                "10012345000017", // GTIN-14
                "8410283381758", // the real product this app contributed to Open Food Facts
            )
            .forEach { assertThat(NutritionPlausibility.isBarcodeUsable(it)).isTrue() }
    }

    @Test
    fun `bad check digits are refused at every supported length`() {
        listOf("96385075", "036000291453", "4006381333932", "10012345000018").forEach {
            assertThat(NutritionPlausibility.isBarcodeUsable(it)).isFalse()
        }
    }

    @Test
    fun `unassigned intermediate GTIN lengths are refused`() {
        listOf("123456789", "1234567890", "12345678901").forEach {
            assertThat(NutritionPlausibility.isBarcodeUsable(it)).isFalse()
        }
    }

    @Test
    fun `non-ASCII digits are refused, and used not to be`() {
        // Char.isDigit delegates to Character.isDigit, which is true for
        // every Unicode Nd character -- about thirty scripts. A GTIN is ASCII by definition, and
        // Open Food Facts stores it as a string, so with isDigit each of these would pass all three
        // contribution gates and could be submitted as a product code.
        listOf(
                "٠١٢٣٤٥٦٧", // Arabic-Indic ٠١٢٣٤٥٦٧
                "०१२३४५६७", // Devanagari ०१२३४५६७
                "０１２３４５６７", // Fullwidth ０１２３４５６７
                "߀߁߂߃߄߅߆߇", // N'Ko
            )
            .forEach { assertThat(NutritionPlausibility.isBarcodeUsable(it)).isFalse() }
    }

    @Test
    fun `a mixed script barcode is refused even where most of it is ASCII`() {
        // The realistic shape of an attack or a mangled scan: one substituted character.
        assertThat(NutritionPlausibility.isBarcodeUsable("841028338175８")).isFalse()
        assertThat(NutritionPlausibility.isBarcodeUsable("٠1234567")).isFalse()
    }

    @Test
    fun `length is bounded at both ends`() {
        assertThat(NutritionPlausibility.isBarcodeUsable("1234567")).isFalse() // 7
        assertThat(NutritionPlausibility.isBarcodeUsable("123456789012345")).isFalse() // 15
    }

    @Test
    fun `absent, empty and blank are all refused rather than throwing`() {
        assertThat(NutritionPlausibility.isBarcodeUsable(null)).isFalse()
        assertThat(NutritionPlausibility.isBarcodeUsable("")).isFalse()
        assertThat(NutritionPlausibility.isBarcodeUsable("        ")).isFalse()
    }

    @Test
    fun `surrounding whitespace is trimmed rather than rejected`() {
        // A scanner or a paste can carry whitespace; the digits are what matter.
        assertThat(NutritionPlausibility.isBarcodeUsable("  5012345678900  ")).isTrue()
    }

    @Test
    fun `anything a QR code could carry is refused`() {
        // Why this rule has to run before the URL is built, not after the response comes back.
        // Each of these is a string a scanner could hand to a lookup.
        listOf(
                "../../../../cgi/nutrient_levels",
                "5012345678900#truncated",
                "5012345678900?fields=all",
                "https://example.invalid/",
                "'; DROP TABLE foods; --",
                "a".repeat(4000),
            )
            .forEach { assertThat(NutritionPlausibility.isBarcodeUsable(it)).isFalse() }
    }
}
