package com.lethio.macros.data.food.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A food from the bundled database. Read-only at runtime; built by `tools/build_nutrition_db.py`,
 * which generates its DDL from Room's exported schema so the two cannot drift.
 */
@Entity(
    tableName = "foods",
    indices = [
        // No index on `barcode`: scans resolve through [FoodBarcodeEntity].
        Index(value = ["quality_score"]),
        Index(value = ["basic_rank"]),
    ],
)
data class FoodEntity(
    @PrimaryKey
    val id: Long,

    val name: String,

    /**
     * [name] lowercased and accent-stripped for the `LIKE` tiers. SQLite's `LOWER()` folds ASCII
     * only. Built by `fold_name()` in the pipeline; `foldForSearch` must agree with it.
     */
    @ColumnInfo(name = "name_folded")
    val nameFolded: String,

    /**
     * Language of [name], or null for Open Food Facts, whose names come off the packaging in
     * whatever language it uses.
     */
    val lang: String? = null,

    val brand: String? = null,
    val barcode: String? = null,

    @ColumnInfo(name = "kcal_100g")
    val kcal100g: Double,

    @ColumnInfo(name = "protein_100g")
    val protein100g: Double,

    @ColumnInfo(name = "fat_100g")
    val fat100g: Double,

    /**
     * Carbohydrate as the publisher declared it, never converted. [carbsConvention] says which
     * definition it is, so the display can convert consistently.
     */
    @ColumnInfo(name = "carbs_100g")
    val carbs100g: Double,

    /**
     * `by_difference` (fibre included; USDA, CNF), `available` (fibre excluded) or
     * `available_monosaccharide` (CoFID: monosaccharide equivalents, about x1.11 on starchy foods).
     * Set per source by the pipeline.
     */
    @ColumnInfo(name = "carbs_convention")
    val carbsConvention: String,

    // Null wherever the publisher does not list the value.
    @ColumnInfo(name = "fiber_100g")
    val fiber100g: Double? = null,

    @ColumnInfo(name = "sugar_100g")
    val sugar100g: Double? = null,

    @ColumnInfo(name = "sat_fat_100g")
    val satFat100g: Double? = null,

    @ColumnInfo(name = "sodium_100g")
    val sodium100g: Double? = null,

    /** Grams per millilitre. Required when converting between mass and native volume. */
    @ColumnInfo(name = "density_g_per_ml")
    val densityGPerMl: Double? = null,

    /** The denominator of the `_100g` columns: `g` or `ml`. */
    @ColumnInfo(name = "nutrition_basis", defaultValue = "'g'")
    val nutritionBasis: String = "g",

    @ColumnInfo(name = "nutrition_metadata")
    val nutritionMetadata: String? = null,

    /** The dataset this row comes from (`off`, `usda`, `ciqual`, ...), shown on every result. */
    val source: String,

    /** 0-100 completeness and confidence, assigned by the pipeline. */
    @ColumnInfo(name = "quality_score")
    val qualityScore: Int,

    /**
     * How close the record is to a whole food: 0 basic (raw, plain), 1 prepared, 2 derivative or
     * ultra-processed. Lets *Apples, raw* beat *Apple tart*. Derived from NOVA for Open Food Facts
     * and from the "food, part, preparation" naming of the national tables.
     */
    @ColumnInfo(name = "basic_rank", defaultValue = "1")
    val basicRank: Int = 1,

    /**
     * 0 for national tables, 1 for Open Food Facts, so `banana` returns the fruit before
     * *Bananasplit*. Brand searches are unaffected because FTS requires every token. Set by the
     * pipeline so a new national table cannot be mistaken for branded.
     */
    @ColumnInfo(name = "source_rank", defaultValue = "1")
    val sourceRank: Int = 1,

    /**
     * Folded alternate names, space-joined. Duplicates [FoodAliasEntity] because `food_search` is an
     * external-content FTS5 table and can only index columns of `foods`.
     */
    @ColumnInfo(name = "alias_names")
    val aliasNames: String? = null,

    /**
     * Head words split from compounds in [name] (`Sødmælk` adds `maelk`, `Vollkornbrot` adds
     * `brot`), because an FTS5 prefix query cannot match inside a word. Used for matching only:
     * never displayed and weighted 0 in bm25.
     */
    @ColumnInfo(name = "search_extra")
    val searchExtra: String? = null,

    /**
     * The country the row is sold in: the source's country for national tables, the first listed
     * country for Open Food Facts. Null when unknown.
     */
    @ColumnInfo(name = "country")
    val country: String? = null,
)

/**
 * A name a food can be found and shown by in another language, so one record serves every language
 * (*œuf* and the USDA egg are the same row). Optional region: `en-GB` for *courgette*.
 *
 * `(food_id, lang)` is unique; the display join in `FoodDao` relies on it being 1:1. The diary
 * stores the name shown at logging time, so changing language never relabels past entries.
 */
@Entity(
    tableName = "food_aliases",
    indices = [Index(value = ["food_id", "lang"], unique = true)],
)
data class FoodAliasEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "food_id")
    val foodId: Long,

    /** Two-letter code, optionally with a region: `fr`, `de`, `es`, `en-GB`. */
    val lang: String,

    val name: String,
)

/**
 * Every barcode that resolves to a bundled food, including each record's own canonical code: one
 * table, one lookup path. Each code points at its own package record, even when typed search
 * groups that record under another.
 */
@Entity(tableName = "food_barcodes")
data class FoodBarcodeEntity(
    /** Text, not a number: 28,185 codes begin with a zero, and `1328` must not find `00001328`. */
    @PrimaryKey
    val barcode: String,

    @ColumnInfo(name = "food_id")
    val foodId: Long,
)

/** Named portions for a bundled food, from the publishers' portion data and OFF serving sizes. */
@Entity(
    tableName = "food_servings",
    indices = [Index(value = ["food_id"])],
)
data class FoodServingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "food_id")
    val foodId: Long,

    val label: String,

    val grams: Double,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

/** The database describing itself in one row: build, date, source releases and size. */
@Entity(tableName = "database_meta")
data class DatabaseMetaEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    /** Always [MARKET_UNIVERSAL]: one database with every source and language. */
    val market: String,

    /** Monotonic build number, so "is this download newer" is a comparison, not a guess. */
    val version: Int,

    @ColumnInfo(name = "built_at")
    val builtAt: Long,

    /** Which source releases went in, e.g. "off:2026-08-16,usda:2026-04-30,ciqual:2025-11-03". */
    @ColumnInfo(name = "source_versions")
    val sourceVersions: String,

    @ColumnInfo(name = "food_count")
    val foodCount: Int,
) {
    companion object {
        const val SINGLETON_ID = 1

        /** The one build there is: every source, every language, bundled in the APK. */
        const val MARKET_UNIVERSAL = "universal"
    }
}
