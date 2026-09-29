package com.lethio.macros.ui.log

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

    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })

    val food = state.food

    val grams = state.previewGrams()
    val macros = grams?.let { food?.per100g?.forGrams(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.log_food))

                        Text(
                            text = stringResource(
                                R.string.logging_for_date,
                                if (state.isTargetDateToday) {
                                    stringResource(R.string.today_label)
                                } else {
                                    state.targetDate.format(
                                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
                                    )
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
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
                isError = state.amount.isNotEmpty() && grams == null,

                supportingText = when {
                    state.amount.isEmpty() -> null
                    state.amountValue() == null -> {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    }
                    grams == null -> {
                        { Text(stringResource(R.string.amount_unit_not_convertible)) }
                    }
                    else -> null
                },
            )

            val units = food.availableUnits
            if (units.size > 1) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEach { unit ->
                        FilterChip(
                            selected = state.unit.label == unit.label,
                            onClick = { viewModel.onUnitChanged(unit) },

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
                    Text(
                        stringResource(
                            R.string.nutrition_for,
                            "${state.amountValue()?.formatQuantity() ?: state.amount} " +
                                PortionLabels.of(state.unit),
                            (grams ?: 0.0).roundToInt(),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    MacroPreviewRow(
                        stringResource(R.string.calories),
                        EnergyFormat.value(macros?.calories ?: 0.0).toString(),
                        EnergyFormat.label(),
                    )
                    MacroPreviewRow(
                        stringResource(R.string.protein),
                        String.format("%.1f", macros?.proteinG ?: 0.0),
                        stringResource(R.string.grams_short),
                    )
                    MacroPreviewRow(
                        stringResource(R.string.fat),
                        String.format("%.1f", macros?.fatG ?: 0.0),
                        stringResource(R.string.grams_short),
                    )
                    MacroPreviewRow(
                        stringResource(R.string.carbs) + carbLabelSuffix(food.carbLabel),
                        String.format("%.1f", macros?.carbsG ?: 0.0),
                        stringResource(R.string.grams_short),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save(onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = grams != null && grams > 0 && !state.saving,
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
            "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
