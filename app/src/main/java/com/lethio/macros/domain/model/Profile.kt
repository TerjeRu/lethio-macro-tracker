package com.lethio.macros.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.Period

/** The user's own details. All nullable: logging food needs none of it. Never leaves the device. */
data class Profile(
    val sex: Sex? = null,
    val birthDate: LocalDate? = null,
    val heightCm: Double? = null,
    val activityLevel: ActivityLevel? = null,
    val goalDirection: GoalDirection? = null,
    /** Intended rate of change. Positive magnitude; direction comes from [goalDirection]. */
    val rateKgPerWeek: Double? = null,
    /** Optional; when present the calculator uses Katch-McArdle and ignores [sex]. */
    val bodyFatPercent: Double? = null,
    val massUnit: MassUnit = MassUnit.METRIC,
    val energyUnit: EnergyUnit = EnergyUnit.KCAL,
    val onboardedAt: Instant? = null,
) {
    val hasOnboarded: Boolean get() = onboardedAt != null

    fun ageOn(date: LocalDate): Int? =
        birthDate?.let { Period.between(it, date).years.takeIf { years -> years in 0..130 } }

    /** True when the goal calculator has everything it needs. */
    fun canCalculateGoals(on: LocalDate): Boolean =
        sex != null && heightCm != null && activityLevel != null && ageOn(on) != null
}

enum class Sex(val value: String) {
    FEMALE("female"),
    MALE("male"),
    /** The BMR equations have only two coefficients, so this averages them, and the UI says so. */
    UNSPECIFIED("unspecified");

    companion object {
        fun fromValue(value: String?): Sex? = entries.firstOrNull { it.value == value }
    }
}

/** Multipliers applied to BMR. Standard Mifflin-St Jeor activity factors. */
enum class ActivityLevel(val value: String, val factor: Double) {
    SEDENTARY("sedentary", 1.2),
    LIGHT("light", 1.375),
    MODERATE("moderate", 1.55),
    ACTIVE("active", 1.725),
    VERY_ACTIVE("very_active", 1.9);

    companion object {
        fun fromValue(value: String?): ActivityLevel? = entries.firstOrNull { it.value == value }
    }
}

enum class GoalDirection(val value: String) {
    LOSE("lose"),
    MAINTAIN("maintain"),
    GAIN("gain");

    companion object {
        fun fromValue(value: String?): GoalDirection? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Body units for display; storage is always metric. US and UK imperial differ in weight (pounds vs
 * stone and pounds); see `BodyUnits`. The live preference is `SettingsManager.massUnit`; the
 * profile column is not read.
 */
enum class MassUnit(val value: String) {
    METRIC("metric"),
    IMPERIAL_US("imperial_us"),
    IMPERIAL_UK("imperial_uk");

    /** True when weight is a compound stone-and-pounds entry rather than a single number. */
    val usesStone: Boolean get() = this == IMPERIAL_UK

    /** True when height is feet and inches rather than centimetres. */
    val usesFeetInches: Boolean get() = this != METRIC

    companion object {
        /** Unknown values fall back to metric; the old `"imperial"` maps to [IMPERIAL_US]. */
        fun fromValue(value: String?): MassUnit = when (value) {
            "imperial" -> IMPERIAL_US
            else -> entries.firstOrNull { it.value == value } ?: METRIC
        }
    }
}

/** Display preference only. Energy is always stored in kilocalories. */
enum class EnergyUnit(val value: String, val perKcal: Double) {
    KCAL("kcal", 1.0),
    KJ("kj", 4.184);

    companion object {
        fun fromValue(value: String?): EnergyUnit = entries.firstOrNull { it.value == value } ?: KCAL
    }
}
