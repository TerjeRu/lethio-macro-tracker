package com.lethio.macros.data.food.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "foods",
    indices = [

        Index(value = ["quality_score"]),
        Index(value = ["basic_rank"]),
    ],
)
data class FoodEntity(
    @PrimaryKey
    val id: Long,

    val name: String,

    @ColumnInfo(name = "name_folded")
    val nameFolded: String,

    val lang: String? = null,

    val brand: String? = null,
    val barcode: String? = null,

    @ColumnInfo(name = "kcal_100g")
    val kcal100g: Double,

    @ColumnInfo(name = "protein_100g")
    val protein100g: Double,

    @ColumnInfo(name = "fat_100g")
    val fat100g: Double,

    @ColumnInfo(name = "carbs_100g")
    val carbs100g: Double,

    @ColumnInfo(name = "carbs_convention")
    val carbsConvention: String,

    @ColumnInfo(name = "fiber_100g")
    val fiber100g: Double? = null,

    @ColumnInfo(name = "sugar_100g")
    val sugar100g: Double? = null,

    @ColumnInfo(name = "sat_fat_100g")
    val satFat100g: Double? = null,

    @ColumnInfo(name = "sodium_100g")
    val sodium100g: Double? = null,

    @ColumnInfo(name = "density_g_per_ml")
    val densityGPerMl: Double? = null,

    val source: String,

    @ColumnInfo(name = "quality_score")
    val qualityScore: Int,

    @ColumnInfo(name = "basic_rank", defaultValue = "1")
    val basicRank: Int = 1,

    @ColumnInfo(name = "source_rank", defaultValue = "1")
    val sourceRank: Int = 1,

    @ColumnInfo(name = "alias_names")
    val aliasNames: String? = null,

    @ColumnInfo(name = "search_extra")
    val searchExtra: String? = null,

    @ColumnInfo(name = "country")
    val country: String? = null,
)

@Entity(
    tableName = "food_aliases",
    indices = [Index(value = ["food_id", "lang"], unique = true)],
)
data class FoodAliasEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "food_id")
    val foodId: Long,

    val lang: String,

    val name: String,
)

@Entity(tableName = "food_barcodes")
data class FoodBarcodeEntity(

    @PrimaryKey
    val barcode: String,

    @ColumnInfo(name = "food_id")
    val foodId: Long,
)

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

@Entity(tableName = "database_meta")
data class DatabaseMetaEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    val market: String,

    val version: Int,

    @ColumnInfo(name = "built_at")
    val builtAt: Long,

    @ColumnInfo(name = "source_versions")
    val sourceVersions: String,

    @ColumnInfo(name = "food_count")
    val foodCount: Int,
) {
    companion object {
        const val SINGLETON_ID = 1

        const val MARKET_UNIVERSAL = "universal"
    }
}
