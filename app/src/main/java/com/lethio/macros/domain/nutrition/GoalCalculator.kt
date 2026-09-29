package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Sex
import java.time.LocalDate
import kotlin.math.roundToInt

object GoalCalculator {

    const val KCAL_PER_KG = 7700.0

    const val DAYS_PER_WEEK = 7.0

    const val FAT_FRACTION_OF_ENERGY = 0.28

    const val MIN_CARBS_G = 50.0

    const val MIN_CALORIES_FEMALE = 1200.0
    const val MIN_CALORIES_MALE = 1500.0

    const val MIN_BODY_FAT_PERCENT = 3.0
    const val MAX_BODY_FAT_PERCENT = 70.0

    const val MAX_RATE_KG_PER_WEEK = 1.0
    const val MAX_RATE_FRACTION_OF_BODYWEIGHT = 0.01

    const val MIN_WEIGHT_KG = 10.0
    const val MAX_WEIGHT_KG = 650.0
    const val MIN_HEIGHT_CM = 50.0
    const val MAX_HEIGHT_CM = 280.0

    fun basalMetabolicRate(
        sex: Sex,
        weightKg: Double,
        heightCm: Double,
        ageYears: Int,
    ): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        return base + when (sex) {
            Sex.MALE -> 5.0
            Sex.FEMALE -> -161.0
            Sex.UNSPECIFIED -> (5.0 + -161.0) / 2.0
        }
    }

    fun katchMcArdleBmr(weightKg: Double, bodyFatPercent: Double): Double? {
        if (!NumericInput.isUsable(weightKg) || !NumericInput.isUsable(bodyFatPercent)) return null
        if (weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) return null
        if (bodyFatPercent < MIN_BODY_FAT_PERCENT || bodyFatPercent > MAX_BODY_FAT_PERCENT) {
            return null
        }
        val leanMassKg = weightKg * (1.0 - bodyFatPercent / 100.0)
        return 370.0 + 21.6 * leanMassKg
    }

    fun maxRateKgPerWeek(weightKg: Double): Double =
        minOf(MAX_RATE_KG_PER_WEEK, weightKg * MAX_RATE_FRACTION_OF_BODYWEIGHT)

    fun recommendationFloor(sex: Sex?): Double =
        if (sex == Sex.FEMALE) MIN_CALORIES_FEMALE else MIN_CALORIES_MALE

    fun warnThreshold(sex: Sex?): Double =
        if (sex == Sex.MALE) MIN_CALORIES_MALE else MIN_CALORIES_FEMALE

    fun bmrFor(profile: Profile, currentWeightKg: Double?, on: LocalDate = LocalDate.now()): Double? {
        val weight = currentWeightKg ?: return null
        if (!NumericInput.isUsable(weight)) return null
        if (weight < MIN_WEIGHT_KG || weight > MAX_WEIGHT_KG) return null

        profile.bodyFatPercent?.let { return katchMcArdleBmr(weight, it) }

        val sex = profile.sex ?: return null
        val height = profile.heightCm ?: return null
        val age = profile.ageOn(on) ?: return null
        if (!NumericInput.isUsable(height)) return null
        if (height < MIN_HEIGHT_CM || height > MAX_HEIGHT_CM) return null
        return basalMetabolicRate(sex, weight, height, age)
    }

    fun totalDailyEnergyExpenditure(bmr: Double, activityLevel: ActivityLevel): Double =
        bmr * activityLevel.factor

    fun calculate(
        profile: Profile,
        currentWeightKg: Double?,
        on: LocalDate = LocalDate.now(),
    ): Goals? {
        val activity = profile.activityLevel ?: return null
        val weight = currentWeightKg ?: return null

        val bmr = bmrFor(profile, weight, on) ?: return null
        val tdee = totalDailyEnergyExpenditure(bmr, activity)

        val requestedRate = profile.rateKgPerWeek ?: 0.0
        if (!NumericInput.isUsable(requestedRate)) return null
        val rate = requestedRate.coerceIn(0.0, maxRateKgPerWeek(weight))
        val dailyAdjustment = rate * KCAL_PER_KG / DAYS_PER_WEEK
        val target = when (profile.goalDirection) {
            GoalDirection.LOSE -> tdee - dailyAdjustment
            GoalDirection.GAIN -> tdee + dailyAdjustment
            GoalDirection.MAINTAIN, null -> tdee
        }

        val floor = maxOf(bmr, recommendationFloor(profile.sex))

        if (!NumericInput.isUsable(target)) return null
        val calories = maxOf(target, floor).roundToInt().toDouble()

        return Goals(
            effectiveFrom = on,
            targets = splitMacros(calories, weight, profile.goalDirection),
            source = Goals.Source.CALCULATED,
        )
    }

    fun splitMacros(
        calories: Double,
        weightKg: Double,
        direction: GoalDirection?,
    ): Macros {
        val proteinPerKg = when (direction) {
            GoalDirection.LOSE -> 2.0
            GoalDirection.GAIN -> 1.8
            GoalDirection.MAINTAIN, null -> 1.6
        }
        val fatG = (calories * FAT_FRACTION_OF_ENERGY / AtwaterCheck.KCAL_PER_G_FAT)
            .roundToInt().toDouble()

        val affordableProteinG = (
            (
                calories -
                    fatG * AtwaterCheck.KCAL_PER_G_FAT -
                    MIN_CARBS_G * AtwaterCheck.KCAL_PER_G_CARBS
                ) / AtwaterCheck.KCAL_PER_G_PROTEIN
            ).coerceAtLeast(0.0).toInt().toDouble()
        val proteinG = (weightKg * proteinPerKg).roundToInt().toDouble()
            .coerceAtMost(affordableProteinG)

        val remaining = calories -
            proteinG * AtwaterCheck.KCAL_PER_G_PROTEIN -
            fatG * AtwaterCheck.KCAL_PER_G_FAT
        val carbsG = (remaining / AtwaterCheck.KCAL_PER_G_CARBS).coerceAtLeast(0.0)
            .roundToInt().toDouble()

        return Macros(
            calories = calories,
            proteinG = proteinG,
            fatG = fatG,
            carbsG = carbsG,
        )
    }
}
