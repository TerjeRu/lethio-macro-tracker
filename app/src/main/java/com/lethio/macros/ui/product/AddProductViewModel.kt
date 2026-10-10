package com.lethio.macros.ui.product

import com.lethio.macros.data.off.isRelayNutritionUsable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.off.OffContributeResult
import com.lethio.macros.data.off.OffContributeService
import com.lethio.macros.data.off.isRelayTextUsable
import com.lethio.macros.data.off.trimForRelay
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.nutrition.AtwaterCheck
import com.lethio.macros.domain.nutrition.NumericInput
import com.lethio.macros.domain.nutrition.NutritionPlausibility
import com.lethio.macros.domain.repository.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The numeric fields, so the screen can mark exactly the one that is wrong. */
enum class ProductField { CALORIES, PROTEIN, FAT, CARBS }

/** What the contribution attempt is doing, for the part of the screen that reports it. */
enum class ContributeState {
    /** Nothing attempted, or the reader chose not to contribute this one. */
    IDLE,

    /** In flight. */
    SENDING,

    /** Open Food Facts took it. Reported, because publishing should never be silent. */
    ACCEPTED,

    /** Refused, or no account configured. Always shown, never hidden behind a vanished button. */
    REFUSED,

    /** Offline or timed out. Nothing was sent and it is worth trying again. */
    UNAVAILABLE,
}

data class AddProductUiState(
    val barcode: String = "",
    val name: String = "",
    val brand: String = "",
    val calories: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    val saving: Boolean = false,
    val contributeState: ContributeState = ContributeState.IDLE,
    /**
     * Open Food Facts has said it lacks this barcode. Contribution is offered only then; otherwise
     * it might create a duplicate.
     */
    val offConfirmedMissing: Boolean = false,
) {
    val macros: Macros
        get() = Macros(
            calories = NumericInput.valueOr(calories, 0.0) ?: 0.0,
            proteinG = NumericInput.valueOr(protein, 0.0) ?: 0.0,
            fatG = NumericInput.valueOr(fat, 0.0) ?: 0.0,
            carbsG = NumericInput.valueOr(carbs, 0.0) ?: 0.0,
        )

    val invalidFields: Set<ProductField>
        get() = buildSet {
            if (NumericInput.isUnusable(calories)) add(ProductField.CALORIES)
            if (NumericInput.isUnusable(protein)) add(ProductField.PROTEIN)
            if (NumericInput.isUnusable(fat)) add(ProductField.FAT)
            if (NumericInput.isUnusable(carbs)) add(ProductField.CARBS)
        }

    /**
     * Stricter than Quick Add: a product is matched by barcode and reused, so its composition must
     * be stated.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            invalidFields.isEmpty() &&
            NutritionPlausibility.isNameUsable(name) &&
            calories.isNotBlank()

    /**
     * Good enough to offer Open Food Facts: the lookup's quality gate plus the relay's 1 kcal
     * minimum. A product can be worth keeping without being worth publishing.
     */
    val canContribute: Boolean
        get() = canSave &&
            offConfirmedMissing &&
            isRelayTextUsable(name, minimumLength = 2) &&
            (brand.trimForRelay().isEmpty() || isRelayTextUsable(brand, minimumLength = 1)) &&
            NutritionPlausibility.isBarcodeUsable(barcode) &&
            isRelayNutritionUsable(macros)

    /** Advisory only: real labels do disagree with their own macros. */
    val energyLooksInconsistent: Boolean
        get() {
            val m = macros
            val anyMacroEntered = m.proteinG > 0 || m.fatG > 0 || m.carbsG > 0
            return m.calories > 0 && anyMacroEntered && !AtwaterCheck.isConsistent(m)
        }

    val suggestedCalories: Int
        get() = AtwaterCheck.predictedCalories(macros).toInt()
}

/**
 * Captures a product the app has never seen: barcode, name, brand and composition per 100 g, so it
 * is found by scanning it again. Saving is local and unconditional; contributing is a separate
 * opt-in, and its failure never costs the saved food.
 */
@HiltViewModel
class AddProductViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val foodRepository: FoodRepository,
    private val offContributeService: OffContributeService,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AddProductUiState(
            barcode = savedStateHandle.get<String>(ARG_BARCODE).orEmpty(),
            offConfirmedMissing = savedStateHandle.get<String>(ARG_OFF_MISSING) == "true",
        ),
    )
    val state: StateFlow<AddProductUiState> = _state.asStateFlow()

    /** Whether contributions are allowed; builds without a relay URL never offer them. */
    val contributeAvailable: StateFlow<Boolean> = settingsManager.offContributeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val contributeConfigured: Boolean = offContributeService.isConfigured

    fun onNameChanged(v: String) = update { copy(name = v) }
    fun onBrandChanged(v: String) = update { copy(brand = v) }
    fun onCaloriesChanged(v: String) = update { copy(calories = v) }
    fun onProteinChanged(v: String) = update { copy(protein = v) }
    fun onFatChanged(v: String) = update { copy(fat = v) }
    fun onCarbsChanged(v: String) = update { copy(carbs = v) }

    private inline fun update(block: AddProductUiState.() -> AddProductUiState) {
        _state.value = _state.value.block()
    }

    /**
     * Saves locally, then contributes if asked. Saving first means an offline phone or a refusal
     * still keeps the food. [onReady] is called once either way, with the saved food, so the reader
     * can go on to log it; after a contribution the outcome is shown first and [proceed] continues.
     */
    fun save(contribute: Boolean, onReady: (FoodRef) -> Unit) {
        val current = _state.value
        if (!current.canSave || current.saving) return

        viewModelScope.launch {
            _state.value = current.copy(saving = true)
            val food = current.toFood()
            val ref = foodRepository.saveCustomFood(food)
            savedRef = ref

            if (contribute && current.canContribute) {
                _state.value = _state.value.copy(contributeState = ContributeState.SENDING)
                val appUuid = settingsManager.offAppUuid()
                val result = offContributeService.contribute(food, appUuid)
                _state.value = _state.value.copy(
                    saving = false,
                    contributeState = when (result) {
                        OffContributeResult.Accepted -> ContributeState.ACCEPTED
                        OffContributeResult.Refused -> ContributeState.REFUSED
                        OffContributeResult.Unavailable -> ContributeState.UNAVAILABLE
                        OffContributeResult.RateLimited -> ContributeState.UNAVAILABLE
                    },
                )
                // onReady waits for the reader to dismiss the outcome; see proceed().
            } else {
                _state.value = _state.value.copy(saving = false)
                onReady(ref)
            }
        }
    }

    /** The food this screen saved, once it has saved one. */
    private var savedRef: FoodRef? = null

    /** Dismisses the contribution outcome and continues to logging the food that was saved. */
    fun proceed(onReady: (FoodRef) -> Unit) {
        savedRef?.let(onReady)
    }

    private fun AddProductUiState.toFood() = Food(
        ref = FoodRef.QuickAdd,
        name = name.trim(),
        brand = brand.trim().takeIf(String::isNotEmpty),
        barcode = barcode.trim().takeIf(String::isNotEmpty),
        per100g = macros,
    )

    private companion object {
        const val ARG_BARCODE = "barcode"
        const val ARG_OFF_MISSING = "offMissing"
    }
}
