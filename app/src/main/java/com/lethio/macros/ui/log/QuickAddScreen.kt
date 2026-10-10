package com.lethio.macros.ui.log

import com.lethio.macros.ui.util.appDateFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.ui.util.labelRes
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddScreen(
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QuickAddViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Next moves down the numeric fields; Done on the last dismisses the keyboard.
    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_add)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scrollable, so large text never cuts off Save.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChanged,
                label = { Text(stringResource(R.string.food_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.calories,
                onValueChange = viewModel::onCaloriesChanged,
                label = { Text(stringResource(R.string.calories)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = QuickAddField.CALORIES in state.invalidFields,
                // Each error sits under its own field, so colour is not the only signal.
                supportingText = if (QuickAddField.CALORIES in state.invalidFields) {
                    { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                } else {
                    null
                },
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.protein,
                    onValueChange = viewModel::onProteinChanged,
                    label = { Text(stringResource(R.string.protein_g)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = imeActions,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = QuickAddField.PROTEIN in state.invalidFields,
                    supportingText = if (QuickAddField.PROTEIN in state.invalidFields) {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = state.fat,
                    onValueChange = viewModel::onFatChanged,
                    label = { Text(stringResource(R.string.fat_g)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = imeActions,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = QuickAddField.FAT in state.invalidFields,
                    supportingText = if (QuickAddField.FAT in state.invalidFields) {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = state.carbs,
                    onValueChange = viewModel::onCarbsChanged,
                    label = { Text(stringResource(R.string.carbs_g)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = imeActions,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = QuickAddField.CARBS in state.invalidFields,
                    supportingText = if (QuickAddField.CARBS in state.invalidFields) {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    } else {
                        null
                    },
                )
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

            Spacer(Modifier.height(16.dp))

            // Cross-field advisories; impossible values are already refused by the button.
            if (state.looksImplausible) {
                Text(
                    stringResource(R.string.value_looks_unusually_large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (state.energyLooksInconsistent) {
                Text(
                    stringResource(
                        R.string.energy_does_not_match_macros,
                        state.suggestedCalories,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.weight(1f))

            Text(stringResource(R.string.diary_add_to_date,
                state.targetDate.format(appDateFormatter())),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.save(onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave && !state.saving,
            ) {
                Text(stringResource(R.string.log_food))
            }
        }
    }
}
