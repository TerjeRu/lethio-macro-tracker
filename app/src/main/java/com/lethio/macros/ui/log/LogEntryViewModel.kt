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
    /** The day the entry is logged to: the day Diary was showing, shown beside Save. */
    val targetDate: LocalDate = LocalDate.MIN,
) {
    /**
     * Whether the food resolved. [MISSING] happens in ordinary use: a favourite can outlive the food
     * it points at.
     */
    enum class Load { LOADING, LOADED, MISSING }

    /** Live preview of the mass to be logged; null while the amount is unparseable. */
    fun previewGrams(): Double? =
        amountValue()?.let { UnitConverter.resolve(it, unit, food)?.grams }

    fun previewQuantity(): Quantity? = amountValue()?.let { UnitConverter.resolve(it, unit, food) }
    fun previewNutrition(): Macros? = previewQuantity()?.let { food?.nutritionFor(it) }

    /** The typed amount as a number, or null when it is empty or unusable. See [NumericInput]. */
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
            // A meal chosen on Diary wins over the time-of-day guess.
            mealType = savedStateHandle.get<String>(ARG_MEAL)?.let { name -> MealType.entries.firstOrNull { it.name == name } }
                ?: MealType.forTime(timeProvider.currentTime()),
            targetDate = currentDiaryDate.value,
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

            // The food's default serving rather than an arbitrary 100 g.
            val defaultServing = food.defaultServing?.takeIf {
                MeasureUnit.Portion(it.label, it.grams) in food.availableUnits
            }
            // Observed, since it can change elsewhere while this screen is open.
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
                    ?.takeIf { it in food.availableUnits }
                    ?: food.nativeUnit,
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

    /** A quick-add has no food record, so it cannot be favourited. */
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
        if (quantity.basisAmount <= 0) return
        val nutrition = food.nutritionFor(quantity) ?: return

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
                    // Snapshotted at log time. Correcting the food later must not rewrite history.
                    macros = nutrition,
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
        const val ARG_MEAL = "meal"
    }
}

/** Formats an amount for display, keeping fractional servings. */
internal fun Double.formatAmount(): String =
    if (this % 1.0 == 0.0) roundToInt().toString() else "%.1f".format(this)
