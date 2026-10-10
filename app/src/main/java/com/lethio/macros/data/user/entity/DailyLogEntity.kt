package com.lethio.macros.data.user.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One logged item. Stores what the user chose (`quantity`, `unit_label`) and the resolved mass, plus
 * the name and brand, so a row still reads correctly after the food database changes.
 */
@Entity(
    tableName = "daily_logs",
    indices = [
        Index(value = ["log_date"]),
        Index(value = ["log_date", "meal_type"]),
        // Serves the recents query, which groups by food reference ordered by recency.
        Index(value = ["food_ref_kind", "food_ref_id"]),
        Index(value = ["created_at"]),
    ],
)
data class DailyLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ISO-8601 local date, `YYYY-MM-DD`. Sorts and compares correctly as text. */
    @ColumnInfo(name = "log_date")
    val logDate: String,

    @ColumnInfo(name = "meal_type")
    val mealType: Int,

    /** `bundled` | `custom` | `quick`. Reference kind, not dataset provenance. */
    @ColumnInfo(name = "food_ref_kind")
    val foodRefKind: String,

    /** Null for quick-add entries, which have no underlying food record. */
    @ColumnInfo(name = "food_ref_id")
    val foodRefId: Long? = null,

    @ColumnInfo(name = "food_name")
    val foodName: String,

    @ColumnInfo(name = "brand")
    val brand: String? = null,

    /** What the user typed, e.g. 1.5. */
    @ColumnInfo(name = "quantity")
    val quantity: Double,

    /** What the user picked: `g`, `ml`, `oz`, or a serving name such as `slice`. */
    @ColumnInfo(name = "unit_label")
    val unitLabel: String,

    /** Physical mass when known; nullable for native-volume entries without density. */
    @ColumnInfo(name = "grams")
    val grams: Double?,

    @ColumnInfo(name = "nutrition_basis", defaultValue = "'g'")
    val nutritionBasis: String = "g",

    /** Null only in legacy rows: their resolved amount is the saved gram mass. */
    @ColumnInfo(name = "basis_amount")
    val basisAmount: Double? = null,

    @ColumnInfo(name = "nutrition_metadata")
    val nutritionMetadata: String? = null,

    val calories: Double,

    @ColumnInfo(name = "protein_g")
    val proteinG: Double,

    @ColumnInfo(name = "fat_g")
    val fatG: Double,

    @ColumnInfo(name = "carbs_g")
    val carbsG: Double,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
