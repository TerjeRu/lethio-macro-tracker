package com.lethio.macros.ui.goals

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Sex
import com.lethio.macros.domain.model.WeightEntry
import com.lethio.macros.domain.nutrition.GoalCalculator
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.ProfileRepository
import com.lethio.macros.domain.repository.WeightRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A negative target is worse than a negative entry: every progress bar measures the day against it.
 * Targets are questioned for being implausibly low as well as high.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        goals: FakeGoalsRepository = FakeGoalsRepository(),
        profiles: FakeProfileRepository = FakeProfileRepository(),
        weights: FakeWeightRepository = FakeWeightRepository(),
    ) = GoalsViewModel(goals, profiles, weights, FixedTimeProvider)

    @Test
    fun `a negative target cannot be saved`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onProteinChanged("-40")

            assertThat(viewModel.state.value.canSave).isFalse()
            viewModel.save()
            runCurrent()

            assertThat(repo.saved).isEmpty()
        }

    @Test
    fun `NaN and Infinity cannot be saved`() =
        runTest(dispatcher) {
            listOf("NaN", "Infinity").forEach { typed ->
                val repo = FakeGoalsRepository()
                val viewModel = viewModel(goals = repo)
                runCurrent()
                viewModel.onCaloriesChanged(typed)

                viewModel.save()
                runCurrent()
                assertThat(repo.saved).isEmpty()
            }
        }

    @Test
    fun `an emptied field still means keep the current target`() =
        runTest(dispatcher) {
            // Clearing a box means
            // "leave this one", not "set it to zero" and not "this is an error".
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onCaloriesChanged("")
            viewModel.onProteinChanged("180")

            assertThat(viewModel.state.value.canSave).isTrue()
            viewModel.save()
            runCurrent()

            val saved = repo.saved.single().targets
            assertThat(saved.calories).isEqualTo(CURRENT.calories)
            assertThat(saved.proteinG).isEqualTo(180.0)
        }

    @Test
    fun `an empty calorie field is not treated as a target of zero`() =
        runTest(dispatcher) {
            // The low-side check has the failure mode the high-side one never could: "keep my
            // current
            // target" reads as 0.0 if `isBelow` treats Empty as a number, and every save that
            // touches
            // only the protein box would be blocked as unsafe.
            val viewModel = viewModel()
            runCurrent()
            viewModel.onCaloriesChanged("")

            assertThat(viewModel.state.value.caloriesUnsafelyLow).isFalse()
            assertThat(viewModel.state.value.canSave).isTrue()
        }

    @Test
    fun `a high but possible target warns and still saves`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onCaloriesChanged("6000")

            assertThat(viewModel.state.value.looksImplausible).isTrue()
            assertThat(viewModel.state.value.canSave).isTrue()
            viewModel.save()
            runCurrent()

            assertThat(repo.saved.single().targets.calories).isEqualTo(6000.0)
        }

    // ---- the two tiers ----------------------------------------------------------

    @Test
    fun `a target below the hard minimum cannot be saved`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onCaloriesChanged("500")

            assertThat(viewModel.state.value.caloriesUnsafelyLow).isTrue()
            assertThat(viewModel.state.value.canSave).isFalse()
            viewModel.save()
            runCurrent()

            assertThat(repo.saved).isEmpty()
        }

    @Test
    fun `the hard minimum is rechecked at save, not only in the button`() =
        runTest(dispatcher) {
            // The button's enabled state is a courtesy; this is the guarantee. Quick Add's
            // identical
            // double-check is there because a save that trusts its caller is one refactor from
            // being
            // the hole again, and this is the check least worth losing that way.
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onCaloriesChanged("100")

            viewModel.save()
            runCurrent()

            assertThat(repo.saved).isEmpty()
        }

    @Test
    fun `a low but defensible target warns and still saves`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.onCaloriesChanged("1000")

            val state = viewModel.state.value
            assertThat(state.caloriesBelowFloor).isTrue()
            assertThat(state.caloriesUnsafelyLow).isFalse()
            assertThat(state.canSave).isTrue()

            viewModel.save()
            runCurrent()
            assertThat(repo.saved.single().targets.calories).isEqualTo(1000.0)
        }

    @Test
    fun `the two messages are never shown at once`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.onCaloriesChanged("400")

            val state = viewModel.state.value
            assertThat(state.caloriesUnsafelyLow).isTrue()
            assertThat(state.caloriesBelowFloor).isFalse()
        }

    @Test
    fun `the warn floor is the user's own BMR once a profile exists`() =
        runTest(dispatcher) {
            val profiles =
                FakeProfileRepository(
                    Profile(
                        sex = Sex.MALE,
                        birthDate = LocalDate.of(1991, 8, 29),
                        heightCm = 180.0,
                        activityLevel = ActivityLevel.MODERATE,
                    )
                )
            val weights = FakeWeightRepository(80.0)
            val viewModel = viewModel(profiles = profiles, weights = weights)
            runCurrent()

            // Mifflin-St Jeor for this profile, not the 1,500 constant that would apply without
            // one.
            val expected = GoalCalculator.basalMetabolicRate(Sex.MALE, 80.0, 180.0, 35)
            assertThat(viewModel.state.value.warnFloorCalories.toDouble())
                .isWithin(1.0)
                .of(expected)
        }

    @Test
    fun `with no profile the warn floor is the cautious-about-nagging constant`() =
        runTest(dispatcher) {
            // Unknown sex resolves DOWN here, opposite to GoalCalculator.recommendationFloor. A
            // warning nobody needed teaches people to dismiss the one they did.
            val viewModel = viewModel()
            runCurrent()
            assertThat(viewModel.state.value.warnFloorCalories.toDouble())
                .isEqualTo(GoalCalculator.MIN_CALORIES_FEMALE)
        }

    // ---- the "help me pick" sheet ------------------------------------------------

    private val completeWizard =
        WizardState(
            weight = "80",
            heightCm = "180",
            ageYears = "35",
            sex = Sex.MALE,
            activityLevel = ActivityLevel.MODERATE,
            direction = GoalDirection.MAINTAIN,
        )

    @Test
    fun `the sheet offers nothing until it can answer`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            assertThat(viewModel.previewFromWizard(WizardState())).isNull()
            assertThat(viewModel.previewFromWizard(completeWizard.copy(activityLevel = null)))
                .isNull()
            assertThat(viewModel.previewFromWizard(completeWizard)).isNotNull()
        }

    @Test
    fun `a body fat percentage makes the sex question irrelevant`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            // Katch-McArdle has no sex term, so the sheet hides the question. The calculation has
            // to
            // actually succeed without it, or hiding it would just be a way to fail silently.
            val leanMass = completeWizard.copy(sex = null, bodyFatPercent = "20")
            assertThat(viewModel.previewFromWizard(leanMass)).isNotNull()
            assertThat(viewModel.previewFromWizard(completeWizard.copy(sex = null))).isNull()
        }

    @Test
    fun `applying the sheet fills the fields without saving the goal`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val viewModel = viewModel(goals = repo)
            runCurrent()
            viewModel.openWizard(MassUnit.METRIC)
            runCurrent()
            viewModel.onWizardChanged { completeWizard }
            val expected = viewModel.previewFromWizard(completeWizard)!!

            viewModel.applyWizard()
            runCurrent()

            val state = viewModel.state.value
            assertThat(state.wizard).isNull()
            assertThat(state.calories).isEqualTo(expected.calories.toInt().toString())
            assertThat(state.protein).isEqualTo(expected.proteinG.toInt().toString())
            // The user still presses Save. One writer of Goals, and a suggestion they can edit
            // first.
            assertThat(repo.saved).isEmpty()
        }

    @Test
    fun `applying the sheet remembers the profile and the weight`() =
        runTest(dispatcher) {
            val profiles = FakeProfileRepository()
            val weights = FakeWeightRepository()
            val viewModel = viewModel(profiles = profiles, weights = weights)
            runCurrent()
            viewModel.openWizard(MassUnit.METRIC)
            runCurrent()
            viewModel.onWizardChanged { completeWizard.copy(bodyFatPercent = "18") }

            viewModel.applyWizard()
            runCurrent()

            assertThat(profiles.stored.heightCm).isEqualTo(180.0)
            assertThat(profiles.stored.bodyFatPercent).isEqualTo(18.0)
            assertThat(weights.stored.single().weightKg).isEqualTo(80.0)
        }

    @Test
    fun `an unanswered field is never persisted as a number`() =
        runTest(dispatcher) {
            // The first version of this used a NaN fallback for empty fields. On the lean-mass path
            // nothing reads height, so nothing would have rejected it, and NaN would have gone into
            // the profile row and stayed there.
            val profiles = FakeProfileRepository()
            val viewModel = viewModel(profiles = profiles)
            runCurrent()
            viewModel.openWizard(MassUnit.METRIC)
            runCurrent()
            viewModel.onWizardChanged {
                completeWizard.copy(heightCm = "", sex = null, bodyFatPercent = "20")
            }

            viewModel.applyWizard()
            runCurrent()

            assertThat(profiles.stored.heightCm).isNull()
        }

    @Test
    fun `the sheet keeps the settings it was never asked about`() =
        runTest(dispatcher) {
            val onboarded = Instant.parse("2026-01-01T00:00:00Z")
            val profiles = FakeProfileRepository(Profile(onboardedAt = onboarded))
            val viewModel = viewModel(profiles = profiles)
            runCurrent()
            viewModel.openWizard(MassUnit.METRIC)
            runCurrent()
            viewModel.onWizardChanged { completeWizard }

            viewModel.applyWizard()
            runCurrent()

            assertThat(profiles.stored.onboardedAt).isEqualTo(onboarded)
        }

    @Test
    fun `returning from corrections refreshes inherited targets before another save`() =
        runTest(dispatcher) {
            val repo = FakeGoalsRepository()
            val vm = viewModel(goals = repo)
            runCurrent()
            repo.current = CURRENT.copy(calories = 2300.5)
            vm.refreshAfterCorrections()
            runCurrent()
            assertThat(vm.state.value.calories).isEqualTo("2300.5")
            vm.onCaloriesChanged("")
            vm.save()
            runCurrent()
            assertThat(repo.saved.single().targets.calories).isEqualTo(2300.5)
        }

    private class FakeGoalsRepository : GoalsRepository {
        val saved = mutableListOf<Goals>()
        var current = CURRENT

        override suspend fun goalsFor(date: LocalDate): Goals =
            Goals(effectiveFrom = date, targets = current, source = Goals.Source.MANUAL)

        override suspend fun replaceSavedGoals(
            expected: com.lethio.macros.domain.model.Goals,
            nextDate: java.time.LocalDate?,
            targets: com.lethio.macros.domain.model.Macros,
        ): Unit = error("not used")

        override suspend fun deleteSavedGoals(
            expected: com.lethio.macros.domain.model.Goals,
            nextDate: java.time.LocalDate?,
        ): Unit = error("not used")

        override suspend fun setGoals(goals: Goals) {
            saved += goals
        }

        override fun observeGoalsFor(date: LocalDate): Flow<Goals> = error("not used")

        override fun observeHistory(): Flow<List<Goals>> = error("not used")
    }

    // ---- imperial units ------------------------------------------------------------

    /**
     * The round trip that a reader actually experiences: stored kilograms, prefilled into the sheet
     * in their own units, parsed back. `BodyUnitsTest` sweeps the arithmetic; this checks that the
     * sheet is wired to it in both directions and that storage stays metric.
     */
    @Test
    fun `a UK reader sees stone and pounds and the same kilograms come back`() =
        runTest(dispatcher) {
            // 12 st 4 lb.
            val kg = 12 * 14 * 0.45359237 + 4 * 0.45359237
            val weights = FakeWeightRepository(latestKg = kg)
            val profiles = FakeProfileRepository(Profile(heightCm = 180.0))
            val viewModel = viewModel(profiles = profiles, weights = weights)
            runCurrent()

            viewModel.openWizard(MassUnit.IMPERIAL_UK)
            runCurrent()

            val wizard = viewModel.state.value.wizard!!
            assertThat(wizard.weightStone).isEqualTo("12")
            assertThat(wizard.weightPounds).isEqualTo("4")
            // Height is feet and inches under both imperial modes, not just the US one.
            assertThat(wizard.heightFeet).isEqualTo("5")
            assertThat(wizard.heightInches).isEqualTo("10.9")
            assertThat(wizard.weightKg).isWithin(0.001).of(kg)
        }

    @Test
    fun `a US reader sees pounds and the same kilograms come back`() =
        runTest(dispatcher) {
            val weights = FakeWeightRepository(latestKg = 80.0)
            val viewModel = viewModel(weights = weights)
            runCurrent()

            viewModel.openWizard(MassUnit.IMPERIAL_US)
            runCurrent()

            val wizard = viewModel.state.value.wizard!!
            assertThat(wizard.weightStone).isEmpty()
            assertThat(wizard.weight).isEqualTo("176.4")
            assertThat(wizard.weightKg).isWithin(0.05).of(80.0)
        }

    /**
     * The whole point of the storage rule, asserted against the repository rather than the screen.
     *
     * A reader entering imperial must leave kilograms and centimetres behind them. If this ever
     * fails, every calorie the goal calculator produces is wrong by a factor of 2.2.
     */
    @Test
    fun `entering imperial still stores metric`() =
        runTest(dispatcher) {
            val profiles = FakeProfileRepository()
            val weights = FakeWeightRepository()
            val viewModel = viewModel(profiles = profiles, weights = weights)
            runCurrent()

            viewModel.openWizard(MassUnit.IMPERIAL_UK)
            runCurrent()
            viewModel.onWizardChanged {
                copy(
                    weightStone = "12",
                    weightPounds = "4",
                    heightFeet = "5",
                    heightInches = "10",
                    ageYears = "35",
                    sex = Sex.MALE,
                    activityLevel = ActivityLevel.MODERATE,
                    direction = GoalDirection.MAINTAIN,
                )
            }
            viewModel.applyWizard()
            runCurrent()

            assertThat(weights.stored.single().weightKg).isWithin(0.01).of(78.02)
            assertThat(profiles.stored.heightCm).isWithin(0.01).of(177.8)
        }

    /**
     * Pounds beyond 14 are normalised rather than rejected. Typing "20" into the pounds box is a
     * real quantity written unconventionally, which is `NumericInput`'s implausible-not-impossible
     * line applied to a compound field.
     */
    @Test
    fun `pounds past fourteen roll over instead of being refused`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.openWizard(MassUnit.IMPERIAL_UK)
            runCurrent()
            viewModel.onWizardChanged { copy(weightStone = "0", weightPounds = "20") }

            val kg = viewModel.state.value.wizard!!.weightKg!!
            assertThat(kg).isWithin(0.001).of(20 * 0.45359237)
            val back = com.lethio.macros.domain.nutrition.BodyUnits.kgToStonePounds(kg)
            assertThat(back.stone).isEqualTo(1)
            assertThat(back.pounds).isWithin(0.001).of(6.0)
        }

    /** The rate is pounds a week under both imperial modes, and reaches the domain as kilograms. */
    @Test
    fun `the rate converts and the domain never sees a pound`() =
        runTest(dispatcher) {
            val profiles = FakeProfileRepository()
            val viewModel = viewModel(profiles = profiles, weights = FakeWeightRepository())
            runCurrent()
            viewModel.openWizard(MassUnit.IMPERIAL_US)
            runCurrent()

            // No stored rate, so the sheet opens on the round imperial default -- a pound a week,
            // not 0.5 kg converted into "1.1".
            assertThat(viewModel.state.value.wizard!!.rate).isEqualTo("1")

            viewModel.onWizardChanged {
                copy(
                    weight = "176",
                    heightFeet = "5",
                    heightInches = "10",
                    ageYears = "35",
                    sex = Sex.MALE,
                    activityLevel = ActivityLevel.MODERATE,
                    direction = GoalDirection.LOSE,
                    rate = "1",
                )
            }
            viewModel.applyWizard()
            runCurrent()

            // 1 lb a week is 0.4536 kg a week, and that is what is stored.
            assertThat(profiles.stored.rateKgPerWeek).isWithin(0.001).of(0.45359237)
        }

    /** The other half of the rule: a rate the profile holds is shown exactly, not rounded off. */
    @Test
    fun `a stored rate is converted exactly rather than snapped to the round default`() =
        runTest(dispatcher) {
            val profiles = FakeProfileRepository(Profile(rateKgPerWeek = 0.5))
            val viewModel = viewModel(profiles = profiles)
            runCurrent()
            viewModel.openWizard(MassUnit.IMPERIAL_US)
            runCurrent()
            assertThat(viewModel.state.value.wizard!!.rate).isEqualTo("1.1")
        }

    // ---- the decimal separator -----------------------------------------------------

    /**
     * The wizard's fractional fields take a comma, because seven of eight locales write one.
     *
     * Reached through `WizardState`, which parses with `NumericInput`, so this is really asserting
     * that the compound imperial fields inherit the fix rather than needing their own copy of it.
     */
    @Test
    fun `the wizard reads a comma in every fractional field`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.openWizard(MassUnit.IMPERIAL_UK)
            runCurrent()
            viewModel.onWizardChanged {
                copy(
                    weightStone = "12",
                    weightPounds = "4,5",
                    heightFeet = "5",
                    heightInches = "10,5",
                )
            }

            val wizard = viewModel.state.value.wizard!!
            assertThat(wizard.weightKg).isWithin(0.001).of((12 * 14 + 4.5) * 0.45359237)
            assertThat(wizard.heightCmValue).isWithin(0.001).of((5 * 12 + 10.5) * 2.54)
        }

    /**
     * Age must NOT accept separators. It is parsed with `toIntOrNull` on purpose, and an age has no
     * fractional part.
     */
    @Test
    fun `age still refuses a separator rather than silently becoming no age`() =
        runTest(dispatcher) {
            val viewModel = viewModel(weights = FakeWeightRepository(latestKg = 80.0))
            runCurrent()
            viewModel.openWizard(MassUnit.METRIC)
            runCurrent()
            viewModel.onWizardChanged {
                copy(
                    heightCm = "180",
                    ageYears = "35,5",
                    sex = Sex.MALE,
                    activityLevel = ActivityLevel.MODERATE,
                    direction = GoalDirection.MAINTAIN,
                )
            }

            // No usable age means no estimate at all, which is the intended refusal.
            assertThat(viewModel.previewFromWizard(viewModel.state.value.wizard!!)).isNull()
        }

    private class FakeProfileRepository(var stored: Profile = Profile()) : ProfileRepository {
        override suspend fun getProfile(): Profile = stored

        override suspend fun saveProfile(profile: Profile) {
            stored = profile
        }

        override fun observeProfile(): Flow<Profile> = error("not used")
    }

    private class FakeWeightRepository(latestKg: Double? = null) : WeightRepository {
        val stored = mutableListOf<WeightEntry>()

        init {
            latestKg?.let {
                stored +=
                    WeightEntry(
                        id = 1L,
                        measuredOn = LocalDate.of(2026, 8, 29),
                        weightKg = it,
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    )
            }
        }

        override suspend fun latest(): WeightEntry? = stored.lastOrNull()

        override suspend fun upsert(entry: WeightEntry) {
            stored += entry
        }

        override fun observeEntries(): Flow<List<WeightEntry>> = error("not used")

        override suspend fun delete(id: Long) = error("not used")
    }

    private object FixedTimeProvider : TimeProvider {
        override fun today(): LocalDate = LocalDate.of(2026, 8, 29)

        override fun now(): Instant = Instant.parse("2026-08-29T09:00:00Z")

        override fun currentTime(): LocalTime = LocalTime.of(9, 0)
    }

    private companion object {
        val CURRENT = Macros(calories = 2000.0, proteinG = 150.0, fatG = 65.0, carbsG = 250.0)
    }
}
