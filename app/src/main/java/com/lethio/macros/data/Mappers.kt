package com.lethio.macros.data

import com.lethio.macros.data.food.FoodWithDisplayName
import com.lethio.macros.data.food.foldForSearch
import com.lethio.macros.data.food.entity.FoodEntity
import com.lethio.macros.data.food.entity.FoodServingEntity
import com.lethio.macros.data.user.entity.CustomFoodEntity
import com.lethio.macros.data.user.entity.CustomFoodServingEntity
import com.lethio.macros.data.user.entity.DailyLogEntity
import com.lethio.macros.data.user.entity.FavoriteEntity
import com.lethio.macros.data.user.entity.GoalsEntity
import com.lethio.macros.data.user.entity.ProfileEntity
import com.lethio.macros.data.user.entity.WeightEntryEntity
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.CarbLabel
import com.lethio.macros.domain.model.DataSource
import com.lethio.macros.domain.model.EnergyUnit
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Quantity
import com.lethio.macros.domain.model.Serving
import com.lethio.macros.domain.model.Sex
import com.lethio.macros.domain.model.WeightEntry
import java.time.Instant
import java.time.LocalDate

internal fun String?.toLocalDateOrNull(): LocalDate? =
    runCatching { this?.let(LocalDate::parse) }.getOrNull()

internal fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

internal fun resolveDisplayCarbs(
    rawCarbsG: Double,
    fiberG: Double?,
    convention: String,
): Pair<Double, CarbLabel?> = when (convention) {
    "by_difference" -> if (fiberG != null) {
        (rawCarbsG - fiberG).coerceAtLeast(0.0) to null
    } else {
        rawCarbsG to CarbLabel.INCLUDES_FIBRE
    }
    "available_monosaccharide" -> rawCarbsG to CarbLabel.MONOSACCHARIDE_EQUIVALENT
    else -> rawCarbsG to null
}

fun FoodEntity.toDomain(servings: List<FoodServingEntity> = emptyList()): Food {
    val (displayCarbsG, carbLabel) = resolveDisplayCarbs(carbs100g, fiber100g, carbsConvention)
    return Food(
        ref = FoodRef.Bundled(id),
        name = name,
        brand = brand,
        barcode = barcode,
        per100g = Macros(
            calories = kcal100g,
            proteinG = protein100g,
            fatG = fat100g,
            carbsG = displayCarbsG,
        ),
        servings = servings.map { it.toDomain() },
        densityGPerMl = densityGPerMl,

        provenance = DataSource.fromValue(source),
        qualityScore = qualityScore,
        carbLabel = carbLabel,
    )
}

fun FoodWithDisplayName.toDomain(servings: List<FoodServingEntity> = emptyList()) =
    food.toDomain(servings).copy(name = displayName)

fun FoodServingEntity.toDomain() = Serving(label = label, grams = grams, isDefault = isDefault)

fun CustomFoodEntity.toDomain(servings: List<CustomFoodServingEntity> = emptyList()) = Food(
    ref = FoodRef.Custom(id),
    name = name,
    brand = brand,
    barcode = barcode,
    per100g = Macros(
        calories = kcal100g,
        proteinG = protein100g,
        fatG = fat100g,
        carbsG = carbs100g,
    ),
    servings = servings.map { it.toDomain() },
    densityGPerMl = densityGPerMl,

    provenance = DataSource.fromValue(source) ?: DataSource.USER,
    qualityScore = null,
)

fun CustomFoodServingEntity.toDomain() =
    Serving(label = label, grams = grams, isDefault = isDefault)

fun Food.toCustomFoodEntity(now: Instant, createdAt: Instant = now) = CustomFoodEntity(
    id = (ref as? FoodRef.Custom)?.id ?: 0L,
    name = name,
    brand = brand,
    barcode = barcode,

    nameFolded = foldForSearch(name),
    brandFolded = brand?.let(::foldForSearch),

    source = provenance?.takeIf { it != DataSource.USER }?.value,
    kcal100g = per100g.calories,
    protein100g = per100g.proteinG,
    fat100g = per100g.fatG,
    carbs100g = per100g.carbsG,
    densityGPerMl = densityGPerMl,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = now.toEpochMilli(),
)

fun Serving.toCustomServingEntity(foodId: Long, sortOrder: Int) = CustomFoodServingEntity(
    foodId = foodId,
    label = label,
    grams = grams,
    isDefault = isDefault,
    sortOrder = sortOrder,
)

fun DailyLogEntity.toDomain() = LogEntry(
    id = id,

    date = logDate.toLocalDateOrNull() ?: LocalDate.ofEpochDay(0),
    meal = MealType.fromValue(mealType),
    foodRef = FoodRef.of(FoodRef.Kind.fromValue(foodRefKind), foodRefId),
    foodName = foodName,
    brand = brand,
    quantity = Quantity(
        amount = quantity,

        unit = MeasureUnit.fromLoggedEntry(unitLabel, quantity, grams),
        grams = grams,
    ),
    macros = Macros(
        calories = calories,
        proteinG = proteinG,
        fatG = fatG,
        carbsG = carbsG,
    ),
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

fun LogEntry.toEntity() = DailyLogEntity(
    id = id,
    logDate = date.toString(),
    mealType = meal.value,
    foodRefKind = foodRef.kind.value,
    foodRefId = foodRef.id,
    foodName = foodName,
    brand = brand,
    quantity = quantity.amount,
    unitLabel = quantity.unit.label,
    grams = quantity.grams,
    calories = macros.calories,
    proteinG = macros.proteinG,
    fatG = macros.fatG,
    carbsG = macros.carbsG,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

fun GoalsEntity.toDomain() = Goals(
    effectiveFrom = effectiveFrom.toLocalDateOrNull() ?: LocalDate.ofEpochDay(0),
    targets = Macros(
        calories = caloriesTarget,
        proteinG = proteinTargetG,
        fatG = fatTargetG,
        carbsG = carbsTargetG,
    ),
    source = Goals.Source.fromValue(source),
)

fun Goals.toEntity(now: Instant) = GoalsEntity(
    effectiveFrom = effectiveFrom.toString(),
    caloriesTarget = targets.calories,
    proteinTargetG = targets.proteinG,
    fatTargetG = targets.fatG,
    carbsTargetG = targets.carbsG,
    source = source.value,
    createdAt = now.toEpochMilli(),
)

fun ProfileEntity.toDomain() = Profile(
    sex = Sex.fromValue(sex),
    birthDate = birthDate.toLocalDateOrNull(),
    heightCm = heightCm,
    activityLevel = ActivityLevel.fromValue(activityLevel),
    goalDirection = GoalDirection.fromValue(goalDirection),
    rateKgPerWeek = rateKgPerWeek,
    bodyFatPercent = bodyFatPercent,
    massUnit = MassUnit.fromValue(massUnit),
    energyUnit = EnergyUnit.fromValue(energyUnit),
    onboardedAt = onboardedAt?.toInstant(),
)

fun Profile.toEntity() = ProfileEntity(
    id = ProfileEntity.SINGLETON_ID,
    sex = sex?.value,
    birthDate = birthDate?.toString(),
    heightCm = heightCm,
    activityLevel = activityLevel?.value,
    goalDirection = goalDirection?.value,
    rateKgPerWeek = rateKgPerWeek,
    bodyFatPercent = bodyFatPercent,
    massUnit = massUnit.value,
    energyUnit = energyUnit.value,
    onboardedAt = onboardedAt?.toEpochMilli(),
)

fun WeightEntryEntity.toDomain() = WeightEntry(
    id = id,
    measuredOn = measuredOn.toLocalDateOrNull() ?: LocalDate.ofEpochDay(0),
    weightKg = weightKg,
    note = note,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

fun WeightEntry.toEntity() = WeightEntryEntity(
    id = id,
    measuredOn = measuredOn.toString(),
    weightKg = weightKg,
    note = note,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

fun FavoriteEntity.toRef(): FoodRef =
    FoodRef.of(FoodRef.Kind.fromValue(foodRefKind), foodRefId)
