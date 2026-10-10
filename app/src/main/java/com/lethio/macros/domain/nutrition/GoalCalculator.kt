package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Sex
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Turns a [Profile] and a current weight into daily targets.
 *
 * BMR is Mifflin-St Jeor, or Katch-McArdle when a body fat percentage is on file. Either is an
 * estimate (Mifflin-St Jeor is about ±10%), and the UI presents it as a starting point.
 */
object GoalCalculator {

    /** Energy in roughly one kilogram of body mass, used to size a deficit or surplus. */
    const val KCAL_PER_KG = 7700.0

    const val DAYS_PER_WEEK = 7.0

    /**
     * Fat's share of energy. Below about a fifth, hormone production and fat-soluble vitamin
     * absorption suffer. A share rather than per kg because the need tracks energy, not body mass.
     */
    const val FAT_FRACTION_OF_ENERGY = 0.28

    /**
     * The least carbohydrate [splitMacros] recommends: the conventional ketogenic boundary, so the
     * app never hands out a ketogenic plan nobody asked for. Flat grams rather than a share, which
     * would compound at heavy profiles and push protein under ~1.2 g/kg. No ordinary profile
     * reaches it.
     */
    const val MIN_CARBS_G = 50.0

    /**
     * The conventional floors for unsupervised dieting. Not the hard block, which is
     * [NumericInput.HARD_MIN_CALORIES]. [recommendationFloor] and [warnThreshold] resolve an
     * unknown sex in opposite directions on purpose.
     */
    const val MIN_CALORIES_FEMALE = 1200.0
    const val MIN_CALORIES_MALE = 1500.0

    /** Body fat percentages [katchMcArdleBmr] accepts; outside them is a typo, not a person. */
    const val MIN_BODY_FAT_PERCENT = 3.0
    const val MAX_BODY_FAT_PERCENT = 70.0

    /**
     * Rate caps. The effective cap is the lower of 1 kg and 1% of bodyweight per week, because a
     * flat kilogram is aggressive for light people; see [maxRateKgPerWeek].
     */
    const val MAX_RATE_KG_PER_WEEK = 1.0
    const val MAX_RATE_FRACTION_OF_BODYWEIGHT = 0.01

    /**
     * Generous physiological bounds (the records are 635 kg and 272 cm). They reject entry errors,
     * such as grams or metres, not unusual people.
     */
    const val MIN_WEIGHT_KG = 10.0
    const val MAX_WEIGHT_KG = 650.0
    const val MIN_HEIGHT_CM = 50.0
    const val MAX_HEIGHT_CM = 280.0

    /**
     * Mifflin-St Jeor BMR in kcal/day. [Sex.UNSPECIFIED] averages the two constants; the equation
     * has no third coefficient.
     */
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

    /**
     * Katch-McArdle BMR in kcal/day: `370 + 21.6 x lean body mass`. It has no sex term, so a known
     * body fat percentage makes the sex question unnecessary. Not the default because most people
     * only guess their body fat. Null for a percentage no body has.
     */
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

    /**
     * The lowest target the app recommends when BMR is unavailable. An unknown sex takes the
     * higher floor: raising a floor can only recommend eating more.
     */
    fun recommendationFloor(sex: Sex?): Double =
        if (sex == Sex.FEMALE) MIN_CALORIES_FEMALE else MIN_CALORIES_MALE

    /**
     * Below this a typed target is questioned. An unknown sex takes the lower threshold, because a
     * needless warning teaches people to dismiss warnings.
     */
    fun warnThreshold(sex: Sex?): Double =
        if (sex == Sex.MALE) MIN_CALORIES_MALE else MIN_CALORIES_FEMALE

    /** The best BMR this profile supports: Katch-McArdle with body fat, else Mifflin-St Jeor, else null. */
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

    /** Daily targets, or null when the profile lacks anything required. Never guesses an input. */
    fun calculate(
        profile: Profile,
        currentWeightKg: Double?,
        on: LocalDate = LocalDate.now(),
    ): Goals? {
        val activity = profile.activityLevel ?: return null
        val weight = currentWeightKg ?: return null

        val bmr = bmrFor(profile, weight, on) ?: return null
        val tdee = totalDailyEnergyExpenditure(bmr, activity)

        // `coerceIn` passes NaN through, so check finiteness first. A finite but absurd rate is
        // clamped, not rejected.
        val requestedRate = profile.rateKgPerWeek ?: 0.0
        if (!NumericInput.isUsable(requestedRate)) return null
        val rate = requestedRate.coerceIn(0.0, maxRateKgPerWeek(weight))
        val dailyAdjustment = rate * KCAL_PER_KG / DAYS_PER_WEEK
        val target = when (profile.goalDirection) {
            GoalDirection.LOSE -> tdee - dailyAdjustment
            GoalDirection.GAIN -> tdee + dailyAdjustment
            GoalDirection.MAINTAIN, null -> tdee
        }

        // Never below resting metabolism, and never below the conventional floor even when BMR is.
        val floor = maxOf(bmr, recommendationFloor(profile.sex))
        // Unreachable with the guards above; `roundToInt()` would throw rather than mislead.
        if (!NumericInput.isUsable(target)) return null
        val calories = maxOf(target, floor).roundToInt().toDouble()

        return Goals(
            effectiveFrom = on,
            targets = splitMacros(calories, weight, profile.goalDirection),
            source = Goals.Source.CALCULATED,
        )
    }

    /**
     * Splits an energy target into macro grams that add up: `4·protein + 9·fat + 4·carbs` equals
     * [calories] to within carbohydrate's rounding.
     *
     * Protein is per kilogram, because the need tracks body mass and a percentage split gives less
     * protein in a deficit. Fat is [FAT_FRACTION_OF_ENERGY]; carbohydrate takes the rest above
     * [MIN_CARBS_G]. When they cannot all hold, protein gives way. The calorie target never moves:
     * every floor in this file reasons about it.
     */
    fun splitMacros(
        calories: Double,
        weightKg: Double,
        direction: GoalDirection?,
    ): Macros {
        val proteinPerKg = when (direction) {
            GoalDirection.LOSE -> 2.0   // higher intake preserves lean mass in a deficit
            GoalDirection.GAIN -> 1.8
            GoalDirection.MAINTAIN, null -> 1.6
        }
        val fatG = (calories * FAT_FRACTION_OF_ENERGY / AtwaterCheck.KCAL_PER_G_FAT)
            .roundToInt().toDouble()

        // What remains after fat and the carbohydrate floor. Truncated so protein cannot cross the floor.
        val affordableProteinG = (
            (
                calories -
                    fatG * AtwaterCheck.KCAL_PER_G_FAT -
                    MIN_CARBS_G * AtwaterCheck.KCAL_PER_G_CARBS
                ) / AtwaterCheck.KCAL_PER_G_PROTEIN
            ).coerceAtLeast(0.0).toInt().toDouble()
        val proteinG = (weightKg * proteinPerKg).roundToInt().toDouble()
            .coerceAtMost(affordableProteinG)

        // Carbohydrate absorbs the rounding, which keeps the energy identity exact.
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
