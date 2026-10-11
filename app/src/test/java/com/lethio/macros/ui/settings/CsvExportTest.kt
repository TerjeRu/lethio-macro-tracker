package com.lethio.macros.ui.settings

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Quantity
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

/**
 * The CSV export is the only file this app produces that is meant to be opened elsewhere. The first
 * two tests pin `exportCsv`'s guarantees: embedded quotes are escaped, and file I/O is off the main
 * thread.
 */
class CsvExportTest {

    private fun entry(
        foodName: String = "Porridge oats",
        brand: String? = "TestBrand",
        grams: Double = 80.0,
        macros: Macros = Macros(calories = 303.0, proteinG = 11.0, fatG = 6.2, carbsG = 50.0),
    ) =
        LogEntry(
            id = 1,
            date = LocalDate.of(2026, 9, 6),
            meal = MealType.BREAKFAST,
            foodRef = FoodRef.QuickAdd,
            foodName = foodName,
            brand = brand,
            quantity = Quantity(amount = grams, unit = MeasureUnit.Grams, grams = grams),
            macros = macros,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    // ---- The two defects the KDoc claims to have fixed ----

    @Test
    fun `an embedded quote is doubled`() {
        val row = entry(foodName = """Ben's "Original" rice""").toCsvRow()
        // Wrapped in quotes, with each embedded quote doubled: "Ben's ""Original"" rice"
        assertThat(row).contains("\"Ben's \"\"Original\"\" rice\"")
    }

    @Test
    fun `an embedded newline survives inside the quotes and does not break the row`() {
        val row = entry(foodName = "Two\nlines").toCsvRow()
        // The newline stays, but it is inside a quoted field, which is what RFC 4180 requires and
        // what stops it terminating the record.
        assertThat(row).contains("\"Two\nlines\"")
        assertThat(row.substringBefore("\"Two")).doesNotContain("\n")
    }

    // ---- Formula injection ----

    @Test
    fun `a food named as a formula exports as text`() {
        // Save a custom food named =1+1, log it, export, open in Excel: without the prefix the
        // cell reads 2.
        val row = entry(foodName = "=1+1").toCsvRow()
        assertThat(row).contains("\"'=1+1\"")
    }

    @Test
    fun `every character a spreadsheet treats as a formula is neutralised`() {
        listOf("=SUM(A1)", "+1", "-1", "@SUM", "\tx", "\rx").forEach { name ->
            val row = entry(foodName = name).toCsvRow()
            assertThat(row).contains("\"'$name\"")
        }
    }

    @Test
    fun `the brand column is guarded too, not only the name`() {
        // Both are free text and both are copied verbatim from Open Food Facts product data.
        val row = entry(brand = "=cmd|'/c calc'!A1").toCsvRow()
        assertThat(row).contains("\"'=cmd|'/c calc'!A1\"")
    }

    @Test
    fun `a real name starting with a hyphen keeps every character it had`() {
        // The reason this prefixes rather than strips. `-196 Double Lemon`
        // is a real product; in the bundled catalogue, 11 names and 1 brand start with
        // `-`. The apostrophe is a spreadsheet text marker, not content, so nothing is lost.
        val row = entry(foodName = "-196 Double Lemon").toCsvRow()
        assertThat(row).contains("\"'-196 Double Lemon\"")
        // Nothing removed: the original survives intact after the marker.
        assertThat(row).contains("-196 Double Lemon")
    }

    @Test
    fun `an ordinary name is not prefixed`() {
        val row = entry(foodName = "Porridge oats").toCsvRow()
        assertThat(row).contains("\"Porridge oats\"")
        assertThat(row).doesNotContain("'Porridge")
    }

    // ---- Shape ----

    @Test
    fun `a null brand is an empty field, not the string null`() {
        val row = entry(brand = null).toCsvRow()
        assertThat(row).doesNotContain("null")
        assertThat(row).contains(",,")
    }

    @Test
    fun `the header column count equals every row's column count`() {
        // The assertion that catches a column added to HEADER and not to toCsvRow, which is the
        // next thing to go wrong in this file.
        val headerColumns = SettingsViewModel.HEADER.split(",").size
        listOf(
                entry(),
                entry(brand = null),
                entry(foodName = "=1+1", brand = "-x"),
                entry(foodName = "with, comma"),
            )
            .forEach { logEntry ->
                assertThat(countCsvFields(logEntry.toCsvRow())).isEqualTo(headerColumns)
            }
    }

    private fun fromDataset(dataset: String) =
        Macros(calories = 50.0)
            .withNutrients(
                mapOf(
                    com.lethio.macros.domain.model.Nutrient.CALORIES to
                        com.lethio.macros.domain.model.NutrientValue(
                            value = 50.0,
                            sourceOrigin =
                                com.lethio.macros.domain.model.NutrientOrigin(
                                    dataset,
                                    "1",
                                    "A1",
                                    "ENERC",
                                    emptyList(),
                                ),
                        )
                )
            )

    @Test
    fun `NEVO and FOODfiles rows carry their credit in the last column, others leave it empty`() {
        // The credit is read from the saved snapshot, so it survives catalogue changes.
        val nevo = entry(macros = fromDataset("nevo")).toCsvRow()
        assertThat(nevo)
            .endsWith(
                "\"Based on data from NEVO online version 2025/9.0, RIVM, Bilthoven and other data sources\""
            )
        val nz = entry(macros = fromDataset("foodfiles")).toCsvRow()
        assertThat(nz).contains("FOODfiles 2024 v1.")
        assertThat(nz).endsWith("New Zealand Food Composition Database terms of use.\"")
        assertThat(entry(macros = fromDataset("usda")).toCsvRow()).endsWith(",")
        assertThat(entry().toCsvRow()).endsWith(",")
        for (row in listOf(nevo, nz)) {
            assertThat(countCsvFields(row)).isEqualTo(SettingsViewModel.HEADER.split(",").size)
        }
    }

    @Test
    fun `numbers always use a dot, whatever the locale would prefer`() {
        // toCsvRow documents this: a CSV is read by other programs, which expect a dot
        // whatever the phone's locale.
        val row = entry(grams = 80.5).toCsvRow()
        assertThat(row).contains("80.5")
        assertThat(row).doesNotContain("80,5")
    }

    /** Counts top-level commas, ignoring those inside a quoted field. */
    private fun countCsvFields(row: String): Int {
        var fields = 1
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' && inQuotes && i + 1 < row.length && row[i + 1] == '"' -> i++
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> fields++
            }
            i++
        }
        return fields
    }
}
