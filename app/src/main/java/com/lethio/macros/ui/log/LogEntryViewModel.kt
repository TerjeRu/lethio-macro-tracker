package com.lethio.macros.ui.log

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Quantity
import com.lethio.macros.domain.nutrition.UnitConverter
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.domain.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import com.lethio.macros.domain.nutrition.NumericInput
import kotlin.math.roundToInt

data class LogEntryUiState(
    val food: Food? = null,
    val amount: String = "100",
    val unit: MeasureUnit = MeasureUnit.Grams,
    val mealType: MealType = MealType.SNACK,
    val saving: Boolean = false,
    val isFavorite: Boolean = false,
    val load: Load = Load.LOADING,

    val targetDate: LocalDate = LocalDate.MIN,
    val isTargetDateToday: Boolean = true,
) {

    enum class Load { LOADING, LOADED, MISSING }

    fun previewGrams(): Double? =
        amountValue()?.let { UnitConverter.resolve(it, unit, food)?.grams }

    fun amountValue(): Double? =
        (NumericInput.parse(amount) as? NumericInput.Field.Number)?.value
}

@HiltViewModel
class LogEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val foodRepository: FoodRepository,
    private val logRepository: LogRepository,
    private val timeProvider: TimeProvider,
    private val currentDiaryDate: CurrentDiaryDate,
) : ViewModel() {

    private val foodId: Long = savedStateHandle.get<Long>(ARG_FOOD_ID)
        ?: savedStateHandle.get<Int>(ARG_FOOD_ID)?.toLong()
        ?: 0L
    private val foodKind: String =
        savedStateHandle[ARG_FOOD_KIND] ?: FoodRef.Kind.BUNDLED.value

    private val _state = MutableStateFlow(
        LogEntryUiState(
            mealType = MealType.forTime(timeProvider.currentTime()),
            targetDate = currentDiaryDate.value,
            isTargetDateToday = currentDiaryDate.value == timeProvider.today(),
        ),
    )
    val state: StateFlow<LogEntryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val ref = FoodRef.of(FoodRef.Kind.fromValue(foodKind), foodId)
            val food = foodRepository.getByRef(ref)
            if (food == null) {
                _state.value = _state.value.copy(load = LogEntryUiState.Load.MISSING)
                return@launch
            }

            val defaultServing = food.defaultServing

            launch {
                logRepository.observeIsFavorite(ref).collect { favorite ->
                    _state.value = _state.value.copy(isFavorite = favorite)
                }
            }

            _state.value = _state.value.copy(
                food = food,
                load = LogEntryUiState.Load.LOADED,
                unit = defaultServing
                    ?.let { MeasureUnit.Portion(it.label, it.grams) }
                    ?: MeasureUnit.Grams,
                amount = if (defaultServing != null) "1" else "100",
            )
        }
    }

    fun onAmountChanged(amount: String) {
        _state.value = _state.value.copy(amount = amount)
    }

    fun onUnitChanged(unit: MeasureUnit) {
        _state.value = _state.value.copy(unit = unit)
    }

    fun onMealTypeChanged(mealType: MealType) {
        _state.value = _state.value.copy(mealType = mealType)
    }

    fun toggleFavorite() {
        val food = _state.value.food ?: return
        viewModelScope.launch {
            logRepository.toggleFavorite(
                LogEntry(
                    id = LogEntry.NEW,
                    date = _state.value.targetDate,
                    meal = _state.value.mealType,
                    foodRef = food.ref,
                    foodName = food.name,
                    brand = food.brand,
                    quantity = Quantity.ofGrams(0.0),
                    macros = Macros.ZERO,
                    createdAt = timeProvider.now(),
                    updatedAt = timeProvider.now(),
                ),
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        val food = current.food ?: return
        val amount = current.amountValue() ?: return
        val quantity = UnitConverter.resolve(amount, current.unit, food) ?: return

        viewModelScope.launch {
            _state.value = current.copy(saving = true)
            val now = timeProvider.now()
            logRepository.addEntry(
                LogEntry(
                    id = LogEntry.NEW,
                    date = current.targetDate,
                    meal = current.mealType,
                    foodRef = food.ref,
                    foodName = food.name,
                    brand = food.brand,
                    quantity = quantity,

                    macros = food.per100g.forGrams(quantity.grams),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            onDone()
        }
    }

    companion object {
        const val ARG_FOOD_ID = "foodId"
        const val ARG_FOOD_KIND = "foodSource"
    }
}

internal fun Double.formatAmount(): String =
    if (this % 1.0 == 0.0) roundToInt().toString() else "%.1f".format(this)
