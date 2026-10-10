package com.lethio.macros.ui.log

import androidx.compose.ui.platform.LocalConfiguration
import com.lethio.macros.ui.util.appDateFormatter
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.ui.util.isTrace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.ui.util.EnergyFormat
import com.lethio.macros.ui.util.PortionLabels
import com.lethio.macros.ui.util.carbLabelSuffix
import com.lethio.macros.ui.util.formatQuantity
import com.lethio.macros.ui.util.labelRes
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogEntryScreen(
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: LogEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Next moves down the numeric fields; Done on the last dismisses the keyboard.
    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })

    val food = state.food

    // The resolved quantity in the chosen unit; this is what gets logged.
    val quantity = state.previewQuantity()
    val grams = quantity?.grams
    val macros = state.previewNutrition()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_food)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
                actions = {
                    if (state.food != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (state.isFavorite) {
                                    Icons.Filled.Star
                                } else {
                                    Icons.Outlined.StarOutline
                                },
                                contentDescription = stringResource(
                                    if (state.isFavorite) {
                                        R.string.remove_favorite_cd
                                    } else {
                                        R.string.add_favorite_cd
                                    },
                                ),
                                tint = if (state.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (food == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                // A missing food (a favourite that outlived it) says so instead of loading forever.
                if (state.load == LogEntryUiState.Load.MISSING) {
                    Text(
                        text = stringResource(R.string.food_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.food_unavailable_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onNavigateBack) {
                        Text(stringResource(R.string.go_back))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scrollable, so large text never clips the Log button.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (!food.brand.isNullOrBlank()) {
                Text(
                    text = food.brand,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChanged,
                label = { Text(stringResource(R.string.amount)) },
                suffix = { Text(PortionLabels.of(state.unit)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.amount.isNotEmpty() && quantity == null,
                // Two messages: an unusable number, or a valid number in a unit this food cannot
                // convert (millilitres without a density, a portion without a weight).
                supportingText = when {
                    state.amount.isEmpty() -> null
                    state.amountValue() == null -> {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    }
                    quantity == null -> {
                        { Text(stringResource(R.string.amount_unit_not_convertible)) }
                    }
                    else -> null
                },
            )

            // Units, including the food's own named servings ("1 slice").
            val units = food.availableUnits
            if (units.size > 1) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEach { unit ->
                        FilterChip(
                            selected = state.unit.label == unit.label,
                            onClick = { viewModel.onUnitChanged(unit) },
                            // With the gram weight: a US, UK and metric cup all differ.
                            label = { Text(PortionLabels.withGrams(unit)) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.meal),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            // FlowRow, so the meal chips wrap instead of squeezing at large text.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.ordered.forEach { meal ->
                    FilterChip(
                        selected = state.mealType == meal,
                        onClick = { viewModel.onMealTypeChanged(meal) },
                        label = { Text(stringResource(meal.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // "1 × 1 cup (250 mL)" when a portion name starts with a number; no "(100 g)"
                    // echo when the amount is already in grams.
                    val unitLabel = PortionLabels.of(state.unit)
                    val amountText = (state.amountValue()?.formatQuantity() ?: state.amount) +
                        (if (state.unit is MeasureUnit.Portion && unitLabel.firstOrNull()?.isDigit() == true)
                            " × " else " ") + unitLabel
                    Text(
                        when {
                            grams == null -> amountText
                            state.unit == MeasureUnit.Grams -> stringResource(R.string.nutrition_for_amount, amountText)
                            else -> stringResource(R.string.nutrition_for, amountText, grams.roundToInt())
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    MacroPreviewRow(
                        stringResource(R.string.calories),
                        EnergyFormat.value(macros?.calories ?: 0.0).toString(),
                        EnergyFormat.label(),
                    )
                    // A trace or below-detection figure reads "trace", without a unit.
                    for ((label, kind, grams) in listOf(
                        Triple(stringResource(R.string.protein), Nutrient.PROTEIN, macros?.proteinG),
                        Triple(stringResource(R.string.fat), Nutrient.FAT, macros?.fatG),
                        Triple(stringResource(R.string.carbs) + carbLabelSuffix(food.carbLabel),
                            Nutrient.CARBS, macros?.carbsG),
                    )) {
                        val trace = macros?.isTrace(kind) == true
                        MacroPreviewRow(
                            label,
                            if (trace) stringResource(R.string.nutrient_trace)
                            else String.format(LocalConfiguration.current.locales[0], "%.1f", grams ?: 0.0),
                            if (trace) "" else stringResource(R.string.grams_short),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.diary_add_to_date,
                state.targetDate.format(appDateFormatter())),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.save(onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = quantity != null && quantity.basisAmount > 0 && macros != null && !state.saving,
            ) {
                Text(stringResource(R.string.log_food))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MacroPreviewRow(label: String, value: String, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (unit.isEmpty()) value else "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
