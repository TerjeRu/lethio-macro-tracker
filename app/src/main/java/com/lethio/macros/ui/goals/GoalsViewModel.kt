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
import com.lethio.macros.domain.nutrition.NumericInput
import com.lethio.macros.domain.repository.GoalsRepository
import com.lethio.macros.domain.repository.ProfileRepository
import com.lethio.macros.domain.repository.WeightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class GoalsUiState(
    val calories: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    val saved: Boolean = false,

    val warnFloorCalories: Int = GoalCalculator.warnThreshold(null).toInt(),

    val wizard: WizardState? = null,
) {

    val invalidFields: Set<GoalField>
        get() = buildSet {
            if (NumericInput.isUnusable(calories)) add(GoalField.CALORIES)
            if (NumericInput.isUnusable(protein)) add(GoalField.PROTEIN)
            if (NumericInput.isUnusable(fat)) add(GoalField.FAT)
            if (NumericInput.isUnusable(carbs)) add(GoalField.CARBS)
        }

    val caloriesUnsafelyLow: Boolean
        get() = NumericInput.isBelow(calories, NumericInput.HARD_MIN_CALORIES)

    val canSave: Boolean get() = invalidFields.isEmpty() && !caloriesUnsafelyLow

    val caloriesBelowFloor: Boolean
        get() = !caloriesUnsafelyLow && NumericInput.isBelow(calories, warnFloorCalories.toDouble())

    val looksImplausible: Boolean
        get() = NumericInput.isImplausible(calories, NumericInput.PLAUSIBLE_MAX_CALORIES) ||
            listOf(protein, fat, carbs).any {
                NumericInput.isImplausible(it, NumericInput.PLAUSIBLE_MAX_GRAMS)
            }

    private val macros: Macros
        get() = Macros(
            calories = NumericInput.valueOr(calories, 0.0) ?: 0.0,
            proteinG = NumericInput.valueOr(protein, 0.0) ?: 0.0,
            fatG = NumericInput.valueOr(fat, 0.0) ?: 0.0,
            carbsG = NumericInput.valueOr(carbs, 0.0) ?: 0.0,
        )

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

enum class GoalField { CALORIES, PROTEIN, FAT, CARBS }

data class WizardState(

    val massUnit: MassUnit = MassUnit.METRIC,

    val weight: String = "",

    val weightStone: String = "",

    val weightPounds: String = "",

    val heightCm: String = "",
    val heightFeet: String = "",
    val heightInches: String = "",
    val ageYears: String = "",
    val sex: Sex? = null,
    val bodyFatPercent: String = "",
    val activityLevel: ActivityLevel? = null,
    val direction: GoalDirection? = null,

    val rate: String = "0.5",
) {

    companion object {

        fun defaultRate(unit: MassUnit): Double =
            if (unit == MassUnit.METRIC) 0.5 else 1.0
    }

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

    val rateKgPerWeek: Double?
        get() = number(rate)?.let { BodyUnits.rateToKg(it, massUnit) }

    private fun number(text: String): Double? =
        (NumericInput.parse(text) as? NumericInput.Field.Number)?.value

    val usesLeanMass: Boolean
        get() = NumericInput.parse(bodyFatPercent) is NumericInput.Field.Number

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
                warnFloorCalories = resolveWarnFloor(),
            )
        }
    }

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

    fun previewFromWizard(wizard: WizardState): Macros? {
        val weight = wizard.weightKg ?: return null
        val profile = wizardProfile(wizard) ?: return null
        return GoalCalculator.calculate(profile, weight, timeProvider.today())?.targets
    }

    private fun number(text: String): Double? =
        (NumericInput.parse(text) as? NumericInput.Field.Number)?.value

    private fun wizardProfile(wizard: WizardState): Profile? {
        val today = timeProvider.today()
        val age = wizard.ageYears.trim().toIntOrNull()
        val rate = if (wizard.needsRate) wizard.rateKgPerWeek ?: return null else 0.0
        return Profile(
            sex = wizard.sex,

            birthDate = age?.let { today.minusYears(it.toLong()) },
            heightCm = wizard.heightCmValue,
            activityLevel = wizard.activityLevel,
            goalDirection = wizard.direction ?: GoalDirection.MAINTAIN,
            rateKgPerWeek = rate,
            bodyFatPercent = number(wizard.bodyFatPercent),
        )
    }

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
        _state.value = _state.value.block()
    }

    private fun formatInput(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

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

    fun save() {
        val s = _state.value

        if (!s.canSave) return
        val today = timeProvider.today()
        viewModelScope.launch {
            val current = goalsRepository.goalsFor(today).targets

            goalsRepository.setGoals(
                Goals(
                    effectiveFrom = today,
                    targets = Macros(
                        calories = NumericInput.valueOr(s.calories, current.calories)
                            ?: current.calories,
                        proteinG = NumericInput.valueOr(s.protein, current.proteinG)
                            ?: current.proteinG,
                        fatG = NumericInput.valueOr(s.fat, current.fatG) ?: current.fatG,
                        carbsG = NumericInput.valueOr(s.carbs, current.carbsG) ?: current.carbsG,
                    ),
                    source = Goals.Source.MANUAL,
                ),
            )
            _state.value = _state.value.copy(saved = true)
        }
    }
}
