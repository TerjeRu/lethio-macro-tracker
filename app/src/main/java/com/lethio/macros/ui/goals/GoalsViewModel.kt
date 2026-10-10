package com.lethio.macros.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.Sex
import com.lethio.macros.domain.model.WeightEntry
import com.lethio.macros.domain.nutrition.AtwaterCheck
import com.lethio.macros.domain.nutrition.BodyUnits
import com.lethio.macros.domain.nutrition.GoalCalculator
import com.lethio.macros.domain.nutrition.GoalTargetLimits
import com.lethio.macros.domain.nutrition.NumericInput
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.ProfileRepository
import com.lethio.macros.domain.repository.WeightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlin.math.roundToInt

data class GoalsUiState(
    val calories: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    val saved: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val inheritedTargets: Macros = Goals.default(java.time.LocalDate.MIN).targets,
    /**
     * Below this a typed calorie target is questioned but still saved: the reader's own BMR when
     * known, otherwise the conventional constant.
     */
    val warnFloorCalories: Int = GoalCalculator.warnThreshold(null).toInt(),
    /** Non-null while the "help me pick" sheet is open. */
    val wizard: WizardState? = null,
) {
    /** Fields holding something no target can be (negative, non-finite). */
    val invalidFields: Set<GoalField>
        get() = buildSet {
            if (NumericInput.isUnusable(calories)) add(GoalField.CALORIES)
            if (NumericInput.isUnusable(protein)) add(GoalField.PROTEIN)
            if (NumericInput.isUnusable(fat)) add(GoalField.FAT)
            if (NumericInput.isUnusable(carbs)) add(GoalField.CARBS)
        }

    /** A target the app refuses to record. See [NumericInput.HARD_MIN_CALORIES]. */
    val caloriesUnsafelyLow: Boolean
        get() = resolvedTargets()?.calories?.let { it < NumericInput.HARD_MIN_CALORIES } == true

    val aboveMaxFields: Set<GoalField>
        get() = buildSet {
            val m = resolvedTargets() ?: return@buildSet
            if (m.calories > GoalTargetLimits.MAX_CALORIES) add(GoalField.CALORIES)
            if (m.proteinG > GoalTargetLimits.MAX_GRAMS) add(GoalField.PROTEIN)
            if (m.fatG > GoalTargetLimits.MAX_GRAMS) add(GoalField.FAT)
            if (m.carbsG > GoalTargetLimits.MAX_GRAMS) add(GoalField.CARBS)
        }

    fun resolvedTargets(): Macros? {
        return Macros(
            NumericInput.valueOr(calories, inheritedTargets.calories) ?: return null,
            NumericInput.valueOr(protein, inheritedTargets.proteinG) ?: return null,
            NumericInput.valueOr(fat, inheritedTargets.fatG) ?: return null,
            NumericInput.valueOr(carbs, inheritedTargets.carbsG) ?: return null,
        )
    }

    val canSave: Boolean get() = !saving && invalidFields.isEmpty() &&
        resolvedTargets()?.let(GoalTargetLimits::accepts) == true

    /** Low but defensible: warned, never blocked, and hidden when the harder message shows. */
    val caloriesBelowFloor: Boolean
        get() = !caloriesUnsafelyLow && NumericInput.isBelow(calories, warnFloorCalories.toDouble())

    /** Advisory only: a 5,000 kcal target is high, not impossible. */
    val looksImplausible: Boolean
        get() = NumericInput.isImplausible(calories, NumericInput.PLAUSIBLE_MAX_CALORIES) ||
            listOf(protein, fat, carbs).any {
                NumericInput.isImplausible(it, NumericInput.PLAUSIBLE_MAX_GRAMS)
            }

    /** The typed targets, for the Atwater cross-check Quick Add also runs. */
    private val macros: Macros
        get() = Macros(
            calories = NumericInput.valueOr(calories, 0.0) ?: 0.0,
            proteinG = NumericInput.valueOr(protein, 0.0) ?: 0.0,
            fatG = NumericInput.valueOr(fat, 0.0) ?: 0.0,
            carbsG = NumericInput.valueOr(carbs, 0.0) ?: 0.0,
        )

    /** Advisory only; never blocks saving. */
    val energyLooksInconsistent: Boolean
        get() {
            val m = macros
            val anyMacroEntered = m.proteinG > 0 || m.fatG > 0 || m.carbsG > 0
            return invalidFields.isEmpty() && m.calories > 0 && anyMacroEntered &&
                !AtwaterCheck.isConsistent(m)
        }

    val suggestedCalories: Int
        get() = AtwaterCheck.predictedCalories(macros).toInt()
}

/** The four numeric fields, so the screen can mark exactly the ones that are wrong. */
enum class GoalField { CALORIES, PROTEIN, FAT, CARBS }

/**
 * What the "help me pick" sheet has collected. It fills the four fields behind it and leaves saving
 * to the screen, so `Goals` has one writer. Strings throughout, so half-typed input survives
 * recomposition.
 */
data class WizardState(
    /** The unit the fields are written in, read from settings when the sheet opens. */
    val massUnit: MassUnit = MassUnit.METRIC,
    /** Weight, in kilograms or pounds per [massUnit]. Unused when [MassUnit.usesStone]. */
    val weight: String = "",
    /** Whole stone. Only under [MassUnit.IMPERIAL_UK]. */
    val weightStone: String = "",
    /** Pounds beyond the stone. Only under [MassUnit.IMPERIAL_UK]. */
    val weightPounds: String = "",
    /** Height in centimetres. Unused when [MassUnit.usesFeetInches]. */
    val heightCm: String = "",
    val heightFeet: String = "",
    val heightInches: String = "",
    val ageYears: String = "",
    val sex: Sex? = null,
    val bodyFatPercent: String = "",
    val activityLevel: ActivityLevel? = null,
    val direction: GoalDirection? = null,
    /** Weekly rate, in kilograms or pounds per [massUnit]. See `BodyUnits.rateToDisplay`. */
    val rate: String = "0.5",
) {

    companion object {
        /**
         * The opening rate when the profile has none: 0.5 kg or 1 lb a week, round in each unit.
         * A stored rate is converted exactly instead (0.5 kg shows as 1.1 lb), never rounded to a
         * neighbour.
         */
        fun defaultRate(unit: MassUnit): Double =
            if (unit == MassUnit.METRIC) 0.5 else 1.0
    }

    /**
     * The typed weight in kilograms, or null. These getters are the only place display units
     * become storage units. Pounds with no stone reads as zero stone.
     */
    val weightKg: Double?
        get() = when {
            !massUnit.usesStone -> number(weight)?.let {
                if (massUnit == MassUnit.METRIC) it else BodyUnits.poundsToKg(it)
            }
            weightStone.isBlank() && weightPounds.isBlank() -> null
            else -> {
                val stone = if (weightStone.isBlank()) 0.0 else number(weightStone) ?: return null
                val pounds = if (weightPounds.isBlank()) 0.0 else number(weightPounds) ?: return null
                BodyUnits.poundsToKg(stone * BodyUnits.POUNDS_PER_STONE + pounds)
            }
        }

    /** The typed height in centimetres, or null. Feet alone or inches alone is a complete answer. */
    val heightCmValue: Double?
        get() = when {
            !massUnit.usesFeetInches -> number(heightCm)
            heightFeet.isBlank() && heightInches.isBlank() -> null
            else -> {
                val feet = if (heightFeet.isBlank()) 0.0 else number(heightFeet) ?: return null
                val inches = if (heightInches.isBlank()) 0.0 else number(heightInches) ?: return null
                BodyUnits.inchesToCm(feet * BodyUnits.INCHES_PER_FOOT + inches)
            }
        }

    /** The typed rate in kilograms per week, or null. */
    val rateKgPerWeek: Double?
        get() = number(rate)?.let { BodyUnits.rateToKg(it, massUnit) }

    private fun number(text: String): Double? =
        (NumericInput.parse(text) as? NumericInput.Field.Number)?.value

    /**
     * True when a body fat percentage is present, which switches to Katch-McArdle. The sex chips
     * are disabled with an explanation rather than hidden, because the percentage is prefilled and
     * a question that vanishes on reopen explains nothing.
     */
    val usesLeanMass: Boolean
        get() = NumericInput.parse(bodyFatPercent) is NumericInput.Field.Number

    /** Rate only means something when there is a direction to travel in. */
    val needsRate: Boolean
        get() = direction == GoalDirection.LOSE || direction == GoalDirection.GAIN
}

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalsRepository: GoalsRepository,
    private val profileRepository: ProfileRepository,
    private val weightRepository: WeightRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(GoalsUiState())
    val state: StateFlow<GoalsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val goals = goalsRepository.goalsFor(timeProvider.today())
            _state.value = _state.value.copy(
                calories = goals.targets.calories.roundToInt().toString(),
                protein = goals.targets.proteinG.roundToInt().toString(),
                fat = goals.targets.fatG.roundToInt().toString(),
                carbs = goals.targets.carbsG.roundToInt().toString(),
                inheritedTargets = goals.targets,
                warnFloorCalories = resolveWarnFloor(),
            )
        }
    }

    /** The reader's BMR when the profile supports one, else the constant for their sex. */
    private suspend fun resolveWarnFloor(): Int {
        val profile = profileRepository.getProfile()
        val weight = weightRepository.latest()?.weightKg
        val bmr = GoalCalculator.bmrFor(profile, weight, timeProvider.today())
        return (bmr ?: GoalCalculator.warnThreshold(profile.sex)).roundToInt()
    }

    fun onCaloriesChanged(v: String) = update { copy(calories = v, saved = false) }
    fun onProteinChanged(v: String) = update { copy(protein = v, saved = false) }
    fun onFatChanged(v: String) = update { copy(fat = v, saved = false) }
    fun onCarbsChanged(v: String) = update { copy(carbs = v, saved = false) }

    // ---- the "help me pick" sheet ---------------------------------------------------------

    /**
     * Opens the sheet prefilled from the profile. [unit] is a snapshot: a unit change mid-edit
     * would reinterpret half-typed text.
     */
    fun openWizard(unit: MassUnit) {
        viewModelScope.launch {
            val profile = profileRepository.getProfile()
            val weight = weightRepository.latest()?.weightKg
            update {
                copy(
                    wizard = prefill(
                        unit = unit,
                        weightKg = weight,
                        profile = profile,
                    ),
                )
            }
        }
    }

    fun dismissWizard() = update { copy(wizard = null) }

    fun onWizardChanged(block: WizardState.() -> WizardState) =
        update { wizard?.let { copy(wizard = it.block()) } ?: this }

    /** The targets the sheet would produce, or null while it cannot answer. */
    fun previewFromWizard(wizard: WizardState): Macros? {
        val weight = wizard.weightKg ?: return null
        val profile = wizardProfile(wizard) ?: return null
        return GoalCalculator.calculate(profile, weight, timeProvider.today())?.targets
    }

    /** A typed field as a number, or null; never NaN, which could be written into the profile. */
    private fun number(text: String): Double? =
        (NumericInput.parse(text) as? NumericInput.Field.Number)?.value

    private fun wizardProfile(wizard: WizardState): Profile? {
        val today = timeProvider.today()
        val age = wizard.ageYears.trim().toIntOrNull()
        val rate = if (wizard.needsRate) wizard.rateKgPerWeek ?: return null else 0.0
        return Profile(
            sex = wizard.sex,
            // Asked as an age; stored as a birth date accurate to the year.
            birthDate = age?.let { today.minusYears(it.toLong()) },
            heightCm = wizard.heightCmValue,
            activityLevel = wizard.activityLevel,
            goalDirection = wizard.direction ?: GoalDirection.MAINTAIN,
            rateKgPerWeek = rate,
            bodyFatPercent = number(wizard.bodyFatPercent),
        )
    }

    /**
     * Writes the sheet's answer into the four fields and closes it, without saving targets: the
     * reader can still adjust them. The profile and weight are saved, since they are true either way.
     */
    fun applyWizard() {
        val wizard = _state.value.wizard ?: return
        val targets = previewFromWizard(wizard) ?: return
        val profile = wizardProfile(wizard) ?: return
        val weight = wizard.weightKg ?: return

        viewModelScope.launch {
            val existing = profileRepository.getProfile()
            profileRepository.saveProfile(
                profile.copy(
                    massUnit = existing.massUnit,
                    energyUnit = existing.energyUnit,
                    onboardedAt = existing.onboardedAt,
                ),
            )
            weightRepository.upsert(
                WeightEntry(
                    id = WeightEntry.NEW,
                    measuredOn = timeProvider.today(),
                    weightKg = weight,
                    createdAt = timeProvider.now(),
                    updatedAt = timeProvider.now(),
                ),
            )
            // Outside `update`: a suspend call inside it compiles only while `update` stays inline.
            val warnFloor = resolveWarnFloor()
            update {
                copy(
                    calories = targets.calories.roundToInt().toString(),
                    protein = targets.proteinG.roundToInt().toString(),
                    fat = targets.fatG.roundToInt().toString(),
                    carbs = targets.carbsG.roundToInt().toString(),
                    saved = false,
                    wizard = null,
                    warnFloorCalories = warnFloor,
                )
            }
        }
    }

    private inline fun update(block: GoalsUiState.() -> GoalsUiState) {
        if (!_state.value.saving) _state.value = _state.value.block().copy(failed = false)
    }

    /** Whole numbers lose their `.0` in a text field; anything else keeps its decimals. */
    private fun formatInput(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

    /** Stored metric values back into the sheet's units; the inverse of `WizardState.weightKg` and friends. */
    private fun prefill(unit: MassUnit, weightKg: Double?, profile: Profile): WizardState {
        val stonePounds = weightKg?.takeIf { unit.usesStone }?.let { BodyUnits.kgToStonePounds(it) }
        val feetInches = profile.heightCm
            ?.takeIf { unit.usesFeetInches }
            ?.let { BodyUnits.cmToFeetInches(it) }
        return WizardState(
            massUnit = unit,
            weight = when {
                weightKg == null || unit.usesStone -> ""
                unit == MassUnit.METRIC -> formatInput(weightKg)
                else -> BodyUnits.formatField(BodyUnits.kgToPounds(weightKg))
            },
            weightStone = stonePounds?.stone?.toString() ?: "",
            weightPounds = stonePounds?.let { BodyUnits.formatField(it.pounds) } ?: "",
            heightCm = if (unit.usesFeetInches) "" else profile.heightCm?.let { formatInput(it) } ?: "",
            heightFeet = feetInches?.feet?.toString() ?: "",
            heightInches = feetInches?.let { BodyUnits.formatField(it.inches) } ?: "",
            ageYears = profile.ageOn(timeProvider.today())?.toString() ?: "",
            sex = profile.sex,
            bodyFatPercent = profile.bodyFatPercent?.let { formatInput(it) } ?: "",
            activityLevel = profile.activityLevel,
            direction = profile.goalDirection,
            rate = profile.rateKgPerWeek
                ?.let { BodyUnits.formatField(BodyUnits.rateToDisplay(it, unit)) }
                ?: BodyUnits.formatField(WizardState.defaultRate(unit)),
        )
    }

    /** Saves a new version effective today, so earlier days keep their targets. */
    fun save() {
        val draft = _state.value
        if (!draft.canSave) return
        val targets = draft.resolvedTargets() ?: return
        val today = timeProvider.today()
        _state.value = draft.copy(saving = true, failed = false)
        viewModelScope.launch {
            try {
                goalsRepository.setGoals(Goals(today, targets, Goals.Source.MANUAL))
                _state.value = draft.copy(saved = true, inheritedTargets = targets)
            } catch (cancelled: CancellationException) {
                _state.value = draft
                throw cancelled
            } catch (_: Exception) {
                _state.value = draft.copy(failed = true)
            }
        }
    }

    /** Returning from saved-goal corrections must not offer yesterday's cached targets. */
    fun refreshAfterCorrections() {
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            try {
                val goals = goalsRepository.goalsFor(timeProvider.today())
                _state.value = _state.value.copy(
                    calories = formatInput(goals.targets.calories),
                    protein = formatInput(goals.targets.proteinG),
                    fat = formatInput(goals.targets.fatG),
                    carbs = formatInput(goals.targets.carbsG),
                    inheritedTargets = goals.targets, saved = false, saving = false, failed = false,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(saving = false, failed = true) }
        }
    }
}
