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

enum class ProductField { CALORIES, PROTEIN, FAT, CARBS }

enum class ContributeState {

    IDLE,

    SENDING,

    ACCEPTED,

    REFUSED,

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

    val canSave: Boolean
        get() = name.isNotBlank() &&
            invalidFields.isEmpty() &&
            NutritionPlausibility.isNameUsable(name) &&
            calories.isNotBlank()

    val canContribute: Boolean
        get() = canSave &&
            offConfirmedMissing &&
            isRelayTextUsable(name, minimumLength = 2) &&
            (brand.trimForRelay().isEmpty() || isRelayTextUsable(brand, minimumLength = 1)) &&
            NutritionPlausibility.isBarcodeUsable(barcode) &&
            isRelayNutritionUsable(macros)

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

            } else {
                _state.value = _state.value.copy(saving = false)
                onReady(ref)
            }
        }
    }

    private var savedRef: FoodRef? = null

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
