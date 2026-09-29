package com.lethio.macros.data.user.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_logs",
    indices = [
        Index(value = ["log_date"]),
        Index(value = ["log_date", "meal_type"]),

        Index(value = ["food_ref_kind", "food_ref_id"]),
        Index(value = ["created_at"]),
    ],
)
data class DailyLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "log_date")
    val logDate: String,

    @ColumnInfo(name = "meal_type")
    val mealType: Int,

    @ColumnInfo(name = "food_ref_kind")
    val foodRefKind: String,

    @ColumnInfo(name = "food_ref_id")
    val foodRefId: Long? = null,

    @ColumnInfo(name = "food_name")
    val foodName: String,

    @ColumnInfo(name = "brand")
    val brand: String? = null,

    @ColumnInfo(name = "quantity")
    val quantity: Double,

    @ColumnInfo(name = "unit_label")
    val unitLabel: String,

    @ColumnInfo(name = "grams")
    val grams: Double,

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
