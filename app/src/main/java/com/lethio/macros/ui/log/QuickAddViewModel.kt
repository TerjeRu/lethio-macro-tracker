package com.lethio.macros.ui.log

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Quantity
import com.lethio.macros.domain.nutrition.AtwaterCheck
import com.lethio.macros.domain.nutrition.NumericInput
import com.lethio.macros.domain.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The four numeric fields, so the screen can mark exactly the ones that are wrong. */
enum class QuickAddField { CALORIES, PROTEIN, FAT, CARBS }

data class QuickAddUiState(
    val name: String = "",
    val calories: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    val mealType: MealType = MealType.SNACK,
    val saving: Boolean = false,
    /** The day the entry is logged to: the day Diary was showing, shown beside Save. */
    val targetDate: LocalDate = LocalDate.MIN,
) {
    /**
     * The typed macros; an empty field means none. Unusable text also reads 0.0 so the live Atwater
     * warning works mid-typing, but [canSave] refuses it.
     */
    val macros: Macros
        get() = Macros(
            calories = NumericInput.valueOr(calories, 0.0) ?: 0.0,
            proteinG = NumericInput.valueOr(protein, 0.0) ?: 0.0,
            fatG = NumericInput.valueOr(fat, 0.0) ?: 0.0,
            carbsG = NumericInput.valueOr(carbs, 0.0) ?: 0.0,
        )

    /** The fields that currently hold something no measurement can be, for marking in the UI. */
    val invalidFields: Set<QuickAddField>
        get() = buildSet {
            if (NumericInput.isUnusable(calories)) add(QuickAddField.CALORIES)
            if (NumericInput.isUnusable(protein)) add(QuickAddField.PROTEIN)
            if (NumericInput.isUnusable(fat)) add(QuickAddField.FAT)
            if (NumericInput.isUnusable(carbs)) add(QuickAddField.CARBS)
        }

    /**
     * Refuses negative and non-finite numbers (checked again in [QuickAddViewModel.save]). A name
     * with no numbers saves: "dinner out, macros unknown" is a real entry.
     */
    val canSave: Boolean get() = name.isNotBlank() && invalidFields.isEmpty()

    /** Advisory: a whole cake really is 4,000 kcal. */
    val looksImplausible: Boolean
        get() = NumericInput.isImplausible(calories, NumericInput.PLAUSIBLE_MAX_CALORIES) ||
            listOf(protein, fat, carbs).any {
                NumericInput.isImplausible(it, NumericInput.PLAUSIBLE_MAX_GRAMS)
            }

    /** Advisory: the macros disagree with the calories, often a typo; labels can disagree too. */
    val energyLooksInconsistent: Boolean
        get() {
            val m = macros
            val anyMacroEntered = m.proteinG > 0 || m.fatG > 0 || m.carbsG > 0
            return m.calories > 0 && anyMacroEntered && !AtwaterCheck.isConsistent(m)
        }

    val suggestedCalories: Int
        get() = AtwaterCheck.predictedCalories(macros).toInt()
}

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val logRepository: LogRepository,
    private val timeProvider: TimeProvider,
    private val currentDiaryDate: CurrentDiaryDate,
) : ViewModel() {

    private val _state = MutableStateFlow(
        QuickAddUiState(
            // Prefilled from Search's "Add manually" fallback.
            name = savedStateHandle.get<String>(ARG_NAME).orEmpty(),
            mealType = savedStateHandle.get<String>(ARG_MEAL)?.let { name -> MealType.entries.firstOrNull { it.name == name } }
                ?: MealType.forTime(timeProvider.currentTime()),
            targetDate = currentDiaryDate.value,
        ),
    )
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    fun onNameChanged(v: String) = update { copy(name = v) }
    fun onCaloriesChanged(v: String) = update { copy(calories = v) }
    fun onProteinChanged(v: String) = update { copy(protein = v) }
    fun onFatChanged(v: String) = update { copy(fat = v) }
    fun onCarbsChanged(v: String) = update { copy(carbs = v) }
    fun onMealTypeChanged(v: MealType) = update { copy(mealType = v) }

    private inline fun update(block: QuickAddUiState.() -> QuickAddUiState) {
        _state.value = _state.value.block()
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        // Checked again here; the save does not trust the button state.
        if (!current.canSave) return

        viewModelScope.launch {
            _state.value = current.copy(saving = true)
            val now = timeProvider.now()
            logRepository.addEntry(
                LogEntry(
                    id = LogEntry.NEW,
                    date = current.targetDate,
                    meal = current.mealType,
                    foodRef = FoodRef.QuickAdd,
                    foodName = current.name.trim(),
                    // The entered macros are totals, recorded as one serving rather than implying grams.
                    quantity = Quantity(1.0, MeasureUnit.Portion("serving", 0.0), 0.0),
                    macros = current.macros,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            onDone()
        }
    }

    private companion object {
        const val ARG_NAME = "name"
        const val ARG_MEAL = "meal"
    }
}
