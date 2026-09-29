package com.lethio.macros.data.user.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "goals",
    indices = [Index(value = ["effective_from"], unique = true)],
)
data class GoalsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "effective_from")
    val effectiveFrom: String,

    @ColumnInfo(name = "calories_target")
    val caloriesTarget: Double,

    @ColumnInfo(name = "protein_target_g")
    val proteinTargetG: Double,

    @ColumnInfo(name = "fat_target_g")
    val fatTargetG: Double,

    @ColumnInfo(name = "carbs_target_g")
    val carbsTargetG: Double,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    val sex: String? = null,

    @ColumnInfo(name = "birth_date")
    val birthDate: String? = null,

    @ColumnInfo(name = "height_cm")
    val heightCm: Double? = null,

    @ColumnInfo(name = "activity_level")
    val activityLevel: String? = null,

    @ColumnInfo(name = "goal_direction")
    val goalDirection: String? = null,

    @ColumnInfo(name = "rate_kg_per_week")
    val rateKgPerWeek: Double? = null,

    @ColumnInfo(name = "body_fat_percent")
    val bodyFatPercent: Double? = null,

    @ColumnInfo(name = "mass_unit")
    val massUnit: String = "metric",

    @ColumnInfo(name = "energy_unit")
    val energyUnit: String = "kcal",

    @ColumnInfo(name = "onboarded_at")
    val onboardedAt: Long? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "weight_entries",
    indices = [Index(value = ["measured_on"], unique = true)],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "measured_on")
    val measuredOn: String,

    @ColumnInfo(name = "weight_kg")
    val weightKg: Double,

    val note: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["food_ref_kind", "food_ref_id"], unique = true),
        Index(value = ["sort_order"]),
    ],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "food_ref_kind")
    val foodRefKind: String,

    @ColumnInfo(name = "food_ref_id")
    val foodRefId: Long,

    @ColumnInfo(name = "food_name")
    val foodName: String,

    val brand: String? = null,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "custom_foods",
    indices = [Index(value = ["barcode"])],
)
data class CustomFoodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val brand: String? = null,
    val barcode: String? = null,

    @ColumnInfo(name = "name_folded", defaultValue = "''")
    val nameFolded: String = "",

    @ColumnInfo(name = "brand_folded")
    val brandFolded: String? = null,

    val source: String? = null,

    @ColumnInfo(name = "kcal_100g")
    val kcal100g: Double,

    @ColumnInfo(name = "protein_100g")
    val protein100g: Double,

    @ColumnInfo(name = "fat_100g")
    val fat100g: Double,

    @ColumnInfo(name = "carbs_100g")
    val carbs100g: Double,

    @ColumnInfo(name = "density_g_per_ml")
    val densityGPerMl: Double? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "custom_food_servings",
    foreignKeys = [
        ForeignKey(
            entity = CustomFoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["food_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["food_id"])],
)
data class CustomFoodServingEntity(
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
