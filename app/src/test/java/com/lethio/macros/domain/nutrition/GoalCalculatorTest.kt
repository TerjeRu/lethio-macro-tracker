package com.lethio.macros.domain.nutrition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Sex
import java.time.LocalDate
import org.junit.Test

class GoalCalculatorTest {

    /**
     * The whole rounding band, and no wider. `carbsG` is the remainder after protein and fat are
     * rounded, so only its own rounding survives into the result: |4 × 0.5| = 2 kcal. A looser
     * tolerance lets a real overrun pass a test written to catch it.
     */
    private val IDENTITY_TOLERANCE = 2.0

    private val today = LocalDate.of(2026, 8, 17)

    private val baseProfile =
        Profile(
            sex = Sex.MALE,
            birthDate = LocalDate.of(1991, 8, 17), // exactly 35 on `today`
            heightCm = 180.0,
            activityLevel = ActivityLevel.MODERATE,
            goalDirection = GoalDirection.MAINTAIN,
        )

    @Test
    fun `Mifflin-St Jeor matches the published formula for men`() {
        // 10*80 + 6.25*180 - 5*35 + 5 = 800 + 1125 - 175 + 5
        val bmr = GoalCalculator.basalMetabolicRate(Sex.MALE, 80.0, 180.0, 35)
        assertThat(bmr).isWithin(0.001).of(1755.0)
    }

    @Test
    fun `Mifflin-St Jeor matches the published formula for women`() {
        // 10*65 + 6.25*165 - 5*30 - 161 = 650 + 1031.25 - 150 - 161
        val bmr = GoalCalculator.basalMetabolicRate(Sex.FEMALE, 65.0, 165.0, 30)
        assertThat(bmr).isWithin(0.001).of(1370.25)
    }

    @Test
    fun `unspecified sex averages the two constants rather than picking one`() {
        val male = GoalCalculator.basalMetabolicRate(Sex.MALE, 70.0, 170.0, 30)
        val female = GoalCalculator.basalMetabolicRate(Sex.FEMALE, 70.0, 170.0, 30)
        val unspecified = GoalCalculator.basalMetabolicRate(Sex.UNSPECIFIED, 70.0, 170.0, 30)
        assertThat(unspecified).isWithin(0.001).of((male + female) / 2)
    }

    @Test
    fun `activity level scales BMR`() {
        val tdee = GoalCalculator.totalDailyEnergyExpenditure(1755.0, ActivityLevel.MODERATE)
        assertThat(tdee).isWithin(0.001).of(1755.0 * 1.55)
    }

    @Test
    fun `maintenance target equals TDEE`() {
        val goals = GoalCalculator.calculate(baseProfile, currentWeightKg = 80.0, on = today)!!
        assertThat(goals.targets.calories).isWithin(1.0).of(1755.0 * 1.55)
        assertThat(goals.source).isEqualTo(Goals.Source.CALCULATED)
    }

    @Test
    fun `losing weight subtracts a deficit sized from the requested rate`() {
        val profile = baseProfile.copy(goalDirection = GoalDirection.LOSE, rateKgPerWeek = 0.5)
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 80.0, on = today)!!
        val maintenance = 1755.0 * 1.55
        val expectedDeficit = 0.5 * GoalCalculator.KCAL_PER_KG / 7.0 // 550 kcal/day
        assertThat(goals.targets.calories).isWithin(1.0).of(maintenance - expectedDeficit)
    }

    @Test
    fun `gaining weight adds a surplus`() {
        val profile = baseProfile.copy(goalDirection = GoalDirection.GAIN, rateKgPerWeek = 0.25)
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 80.0, on = today)!!
        val maintenance = 1755.0 * 1.55
        assertThat(goals.targets.calories).isGreaterThan(maintenance)
    }

    @Test
    fun `never recommends below the safety floor`() {
        // Small, sedentary, asking for an aggressive deficit -- naive arithmetic goes dangerously
        // low here, so the floor has to hold.
        val profile =
            Profile(
                sex = Sex.FEMALE,
                birthDate = LocalDate.of(1966, 8, 17),
                heightCm = 150.0,
                activityLevel = ActivityLevel.SEDENTARY,
                goalDirection = GoalDirection.LOSE,
                rateKgPerWeek = 1.0,
            )
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 50.0, on = today)!!
        assertThat(goals.targets.calories).isAtLeast(GoalCalculator.MIN_CALORIES_FEMALE)
    }

    @Test
    fun `an excessive rate is clamped rather than honoured`() {
        val sane = baseProfile.copy(goalDirection = GoalDirection.LOSE, rateKgPerWeek = 1.0)
        val absurd = baseProfile.copy(goalDirection = GoalDirection.LOSE, rateKgPerWeek = 5.0)
        val a = GoalCalculator.calculate(sane, 80.0, today)!!.targets.calories
        val b = GoalCalculator.calculate(absurd, 80.0, today)!!.targets.calories
        assertThat(b).isEqualTo(a)
    }

    @Test
    fun `returns null on incomplete profile instead of inventing a number`() {
        assertThat(GoalCalculator.calculate(baseProfile.copy(sex = null), 80.0, today)).isNull()
        assertThat(GoalCalculator.calculate(baseProfile.copy(heightCm = null), 80.0, today))
            .isNull()
        assertThat(GoalCalculator.calculate(baseProfile.copy(activityLevel = null), 80.0, today))
            .isNull()
        assertThat(GoalCalculator.calculate(baseProfile.copy(birthDate = null), 80.0, today))
            .isNull()
        assertThat(GoalCalculator.calculate(baseProfile, currentWeightKg = null, on = today))
            .isNull()
    }

    @Test
    fun `macro split is internally consistent with the calorie target`() {
        val macros = GoalCalculator.splitMacros(2400.0, 80.0, GoalDirection.MAINTAIN)
        assertThat(AtwaterCheck.predictedCalories(macros)).isWithin(IDENTITY_TOLERANCE).of(2400.0)
    }

    /**
     * The same assertion as above, swept: a single comfortable profile never brings protein and fat
     * near the target. The tolerance is the true rounding band, because `carbsG` absorbs the
     * rounding of protein and fat exactly:
     * ```
     * predicted = 4·protein + 9·fat + 4·carbs
     *           = 4·protein + 9·fat + (calories − 4·protein − 9·fat) + 4·e
     *           = calories + 4·e        where e is the carbohydrate rounding alone, |e| < 0.5 g
     * ```
     */
    @Test
    fun `no profile in the input space produces macros that disagree with its target`() {
        var checked = 0
        for (weight in 40..650 step 5) {
            for (bodyFat in listOf(null) + (3..70 step 1).toList()) {
                for (direction in GoalDirection.entries) {
                    for (activity in ActivityLevel.entries) {
                        for (sex in listOf(Sex.MALE, Sex.FEMALE, null)) {
                            val profile =
                                baseProfile.copy(
                                    sex = sex,
                                    bodyFatPercent = bodyFat?.toDouble(),
                                    activityLevel = activity,
                                    goalDirection = direction,
                                    rateKgPerWeek = 1.0,
                                )
                            val goals =
                                GoalCalculator.calculate(profile, weight.toDouble(), today)
                                    ?: continue
                            checked++
                            val predicted = AtwaterCheck.predictedCalories(goals.targets)
                            assertWithMessage(
                                    "%skg bf=%s %s/%s/%s: target %s but macros %sp %sf %sc = %s",
                                    weight,
                                    bodyFat,
                                    direction,
                                    activity,
                                    sex,
                                    goals.targets.calories,
                                    goals.targets.proteinG,
                                    goals.targets.fatG,
                                    goals.targets.carbsG,
                                    predicted,
                                )
                                .that(predicted)
                                .isWithin(IDENTITY_TOLERANCE)
                                .of(goals.targets.calories)
                        }
                    }
                }
            }
        }
        // A sweep that silently ran nothing would pass.
        assertThat(checked).isGreaterThan(50_000)
    }

    @Test
    fun `carbohydrate never falls below its floor, however heavy the profile`() {
        // Clamping carbohydrate to zero would keep it non-negative and break the energy identity;
        // the floor holds and protein gives way instead.
        val macros = GoalCalculator.splitMacros(800.0, 120.0, GoalDirection.LOSE)
        assertThat(macros.carbsG).isAtLeast(GoalCalculator.MIN_CARBS_G)
        assertThat(AtwaterCheck.predictedCalories(macros)).isWithin(IDENTITY_TOLERANCE).of(800.0)
    }

    @Test
    fun `protein is what gives way when the target cannot afford all three`() {
        // A worked profile: 140 kg at 65% body fat, losing. Wanted 280 g of protein; the
        // 1500 kcal floor cannot pay for that plus fat plus the carbohydrate floor.
        val macros = GoalCalculator.splitMacros(1500.0, 140.0, GoalDirection.LOSE)
        assertThat(macros.proteinG).isLessThan(280.0)
        assertThat(macros.fatG).isEqualTo(47.0) // the fat share is untouched
        assertThat(macros.carbsG).isAtLeast(GoalCalculator.MIN_CARBS_G)
        assertThat(AtwaterCheck.predictedCalories(macros)).isWithin(IDENTITY_TOLERANCE).of(1500.0)
    }

    @Test
    fun `an ordinary profile is not touched by the carbohydrate floor`() {
        // The floor must be invisible to everyone it was not written for. 80 kg at 25% body fat
        // gets its full 2.0 g/kg of protein and lands well clear of the floor from the remainder.
        val macros = GoalCalculator.splitMacros(1666.0, 80.0, GoalDirection.LOSE)
        assertThat(macros.proteinG).isEqualTo(160.0)
        assertThat(macros.carbsG).isGreaterThan(GoalCalculator.MIN_CARBS_G)
    }

    @Test
    fun `protein scales with body weight not with calories`() {
        // A percentage-of-energy split would cut protein exactly when a deficit demands more.
        val heavy = GoalCalculator.splitMacros(2000.0, 100.0, GoalDirection.MAINTAIN)
        val light = GoalCalculator.splitMacros(2000.0, 60.0, GoalDirection.MAINTAIN)
        assertThat(heavy.proteinG).isGreaterThan(light.proteinG)
    }

    @Test
    fun `cutting raises protein per kilogram relative to maintaining`() {
        val cutting = GoalCalculator.splitMacros(2000.0, 80.0, GoalDirection.LOSE)
        val maintaining = GoalCalculator.splitMacros(2000.0, 80.0, GoalDirection.MAINTAIN)
        assertThat(cutting.proteinG).isGreaterThan(maintaining.proteinG)
    }

    // ---- a nonsense profile must not produce a confident number, or a crash ----

    @Test
    fun `a non-finite weight returns null rather than throwing`() {
        // roundToInt() THROWS on NaN, so this line was a crash on the goals screen, not merely a
        // wrong target. The old guard was `weight <= 0`, and every comparison with NaN is false.
        assertThat(GoalCalculator.calculate(baseProfile, Double.NaN, today)).isNull()
        assertThat(GoalCalculator.calculate(baseProfile, Double.POSITIVE_INFINITY, today)).isNull()
        assertThat(GoalCalculator.calculate(baseProfile, Double.NEGATIVE_INFINITY, today)).isNull()
    }

    @Test
    fun `a non-finite height returns null`() {
        assertThat(GoalCalculator.calculate(baseProfile.copy(heightCm = Double.NaN), 80.0, today))
            .isNull()
        assertThat(
                GoalCalculator.calculate(
                    baseProfile.copy(heightCm = Double.POSITIVE_INFINITY),
                    80.0,
                    today,
                )
            )
            .isNull()
    }

    @Test
    fun `a non-finite rate returns null, because coerceIn does not fix NaN`() {
        // coerceIn(0.0, 1.0) on NaN returns NaN, so clamping alone was never a guard.
        assertThat(
                GoalCalculator.calculate(
                    baseProfile.copy(
                        goalDirection = GoalDirection.LOSE,
                        rateKgPerWeek = Double.NaN,
                    ),
                    80.0,
                    today,
                )
            )
            .isNull()
    }

    @Test
    fun `weights and heights nobody has are refused`() {
        assertThat(GoalCalculator.calculate(baseProfile, 0.0, today)).isNull()
        assertThat(GoalCalculator.calculate(baseProfile, 80_000.0, today)).isNull() // grams
        assertThat(GoalCalculator.calculate(baseProfile.copy(heightCm = 1.8), 80.0, today))
            .isNull() // metres
        assertThat(GoalCalculator.calculate(baseProfile.copy(heightCm = 0.0), 80.0, today)).isNull()
    }

    // ---- Katch-McArdle, the floors, and a rate cap that scales -------------------

    @Test
    fun `Katch-McArdle matches the published formula`() {
        // 80 kg at 20% body fat is 64 kg lean: 370 + 21.6*64 = 370 + 1382.4
        val bmr = GoalCalculator.katchMcArdleBmr(weightKg = 80.0, bodyFatPercent = 20.0)!!
        assertThat(bmr).isWithin(0.001).of(1752.4)
    }

    @Test
    fun `Katch-McArdle needs no sex, height or age`() {
        // The whole reason it is offered: the sheet can skip the sex question entirely rather than
        // ask for an answer the arithmetic never reads.
        val profile =
            Profile(
                activityLevel = ActivityLevel.MODERATE,
                goalDirection = GoalDirection.MAINTAIN,
                bodyFatPercent = 20.0,
            )
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 80.0, on = today)!!
        assertThat(goals.targets.calories).isWithin(1.0).of(1752.4 * 1.55)
    }

    @Test
    fun `a body fat percentage takes precedence over the sex coefficients`() {
        val withBodyFat = baseProfile.copy(bodyFatPercent = 20.0)
        val mifflin = GoalCalculator.calculate(baseProfile, 80.0, today)!!.targets.calories
        val katch = GoalCalculator.calculate(withBodyFat, 80.0, today)!!.targets.calories
        assertThat(katch).isNotEqualTo(mifflin)
        assertThat(katch).isWithin(1.0).of(1752.4 * 1.55)
    }

    @Test
    fun `a body fat percentage no body has is refused, not clamped`() {
        assertThat(GoalCalculator.katchMcArdleBmr(80.0, 0.0)).isNull()
        assertThat(GoalCalculator.katchMcArdleBmr(80.0, 95.0)).isNull()
        assertThat(GoalCalculator.katchMcArdleBmr(80.0, Double.NaN)).isNull()
        assertThat(GoalCalculator.katchMcArdleBmr(80.0, -5.0)).isNull()
        // And it propagates: a nonsense percentage must not silently fall back to Mifflin, which
        // would answer a different question than the one the user's input asked.
        assertThat(GoalCalculator.calculate(baseProfile.copy(bodyFatPercent = 95.0), 80.0, today))
            .isNull()
    }

    @Test
    fun `an unspecified sex gets the cautious floor, not the permissive one`() {
        // This read `if (sex == MALE) MIN_MALE else MIN_FEMALE`, so an unspecified sex took the
        // LOWER floor while averaging its BMR. Raising a floor can only recommend eating more, so
        // the cautious direction for a recommendation is up.
        assertThat(GoalCalculator.recommendationFloor(Sex.UNSPECIFIED))
            .isEqualTo(GoalCalculator.MIN_CALORIES_MALE)
        assertThat(GoalCalculator.recommendationFloor(null))
            .isEqualTo(GoalCalculator.MIN_CALORIES_MALE)
        assertThat(GoalCalculator.recommendationFloor(Sex.FEMALE))
            .isEqualTo(GoalCalculator.MIN_CALORIES_FEMALE)
    }

    @Test
    fun `the warn threshold resolves an unknown sex the other way`() {
        // Not a contradiction with the test above: the two are cautious about different things.
        // A floor is cautious about how little the app tells you to eat; a warning is cautious
        // about how often it interrupts someone whose target was fine.
        assertThat(GoalCalculator.warnThreshold(Sex.UNSPECIFIED))
            .isEqualTo(GoalCalculator.MIN_CALORIES_FEMALE)
        assertThat(GoalCalculator.warnThreshold(null)).isEqualTo(GoalCalculator.MIN_CALORIES_FEMALE)
        assertThat(GoalCalculator.warnThreshold(Sex.MALE))
            .isEqualTo(GoalCalculator.MIN_CALORIES_MALE)
    }

    @Test
    fun `never recommends below resting metabolism`() {
        // A large person can clear the flat constant easily and still be told to eat less than
        // their body burns lying down, which is what the constant on its own missed.
        val profile =
            Profile(
                sex = Sex.MALE,
                birthDate = LocalDate.of(1996, 8, 17),
                heightCm = 190.0,
                activityLevel = ActivityLevel.SEDENTARY,
                goalDirection = GoalDirection.LOSE,
                rateKgPerWeek = 1.0,
            )
        val bmr = GoalCalculator.basalMetabolicRate(Sex.MALE, 140.0, 190.0, 30)
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 140.0, on = today)!!
        assertThat(goals.targets.calories).isAtLeast(bmr)
        assertThat(bmr).isGreaterThan(GoalCalculator.MIN_CALORIES_MALE)
    }

    @Test
    fun `the constant still holds when BMR itself is beneath it`() {
        // The other direction: a small, older, sedentary person can have a BMR under 1,200, and
        // "your own BMR" alone would then permit exactly what the constant exists to prevent.
        val profile =
            Profile(
                sex = Sex.FEMALE,
                birthDate = LocalDate.of(1956, 8, 17),
                heightCm = 150.0,
                activityLevel = ActivityLevel.SEDENTARY,
                goalDirection = GoalDirection.LOSE,
                rateKgPerWeek = 1.0,
            )
        val bmr = GoalCalculator.basalMetabolicRate(Sex.FEMALE, 50.0, 150.0, 70)
        assertThat(bmr).isLessThan(GoalCalculator.MIN_CALORIES_FEMALE)
        val goals = GoalCalculator.calculate(profile, currentWeightKg = 50.0, on = today)!!
        assertThat(goals.targets.calories).isAtLeast(GoalCalculator.MIN_CALORIES_FEMALE)
    }

    @Test
    fun `the rate cap scales with bodyweight`() {
        // A flat kilogram a week is 2% of bodyweight for a 50 kg person and under 1% for a 130 kg
        // one, so the same "maximum" was most aggressive for the people it should protect most.
        assertThat(GoalCalculator.maxRateKgPerWeek(50.0)).isWithin(0.001).of(0.5)
        assertThat(GoalCalculator.maxRateKgPerWeek(80.0)).isWithin(0.001).of(0.8)
        // Still capped absolutely, so it does not keep growing with size.
        assertThat(GoalCalculator.maxRateKgPerWeek(130.0)).isWithin(0.001).of(1.0)
        assertThat(GoalCalculator.maxRateKgPerWeek(300.0)).isWithin(0.001).of(1.0)
    }

    @Test
    fun `a light person asking for a kilogram a week gets the proportional cap`() {
        val profile =
            Profile(
                sex = Sex.FEMALE,
                birthDate = LocalDate.of(1996, 8, 17),
                heightCm = 165.0,
                activityLevel = ActivityLevel.MODERATE,
                goalDirection = GoalDirection.LOSE,
                rateKgPerWeek = 1.0,
            )
        val capped = profile.copy(rateKgPerWeek = 0.5) // 1% of 50 kg
        assertThat(GoalCalculator.calculate(profile, 50.0, today)!!.targets.calories)
            .isEqualTo(GoalCalculator.calculate(capped, 50.0, today)!!.targets.calories)
    }

    @Test
    fun `the bounds are generous enough for the extremes that actually exist`() {
        // Not a judgement about who may use the app: the heaviest person on record
        // was 635 kg and the tallest 272 cm, and both must still get a target.
        assertThat(GoalCalculator.calculate(baseProfile, 635.0, today)).isNotNull()
        assertThat(GoalCalculator.calculate(baseProfile.copy(heightCm = 272.0), 80.0, today))
            .isNotNull()
        assertThat(GoalCalculator.calculate(baseProfile, 40.0, today)).isNotNull()
    }
}
