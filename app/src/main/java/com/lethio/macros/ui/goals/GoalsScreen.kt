package com.lethio.macros.ui.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.ui.util.LocalMassUnit
import com.lethio.macros.domain.nutrition.NumericInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })

    val massUnit = LocalMassUnit.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.daily_goals)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)

                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {

            OutlinedButton(
                onClick = { viewModel.openWizard(massUnit) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.help_me_pick))
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.calories,
                onValueChange = viewModel::onCaloriesChanged,
                label = { Text(stringResource(R.string.calories_kcal)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = GoalField.CALORIES in state.invalidFields || state.caloriesUnsafelyLow,

                supportingText = if (GoalField.CALORIES in state.invalidFields) {
                    { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                } else if (state.caloriesUnsafelyLow) {
                    {
                        Text(
                            stringResource(
                                R.string.calories_below_safe_minimum,
                                NumericInput.HARD_MIN_CALORIES.toInt(),
                            ),
                        )
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.protein,
                onValueChange = viewModel::onProteinChanged,
                label = { Text(stringResource(R.string.protein_g)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = GoalField.PROTEIN in state.invalidFields,
                supportingText = if (GoalField.PROTEIN in state.invalidFields) {
                    { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.fat,
                onValueChange = viewModel::onFatChanged,
                label = { Text(stringResource(R.string.fat_g)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = GoalField.FAT in state.invalidFields,
                supportingText = if (GoalField.FAT in state.invalidFields) {
                    { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.carbs,
                onValueChange = viewModel::onCarbsChanged,
                label = { Text(stringResource(R.string.carbs_g)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = imeActions,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = GoalField.CARBS in state.invalidFields,
                supportingText = if (GoalField.CARBS in state.invalidFields) {
                    { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(16.dp))

            if (state.caloriesBelowFloor) {

                Text(
                    stringResource(R.string.calories_below_floor, state.warnFloorCalories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (state.looksImplausible) {
                Text(
                    stringResource(R.string.value_looks_unusually_large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (state.energyLooksInconsistent) {
                Text(
                    stringResource(R.string.energy_does_not_match_macros, state.suggestedCalories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
            ) {
                Text(stringResource(R.string.save_goals))
            }
            if (state.saved) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.goals_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.wizard?.let { wizard ->
            GoalWizardSheet(
                wizard = wizard,
                preview = viewModel.previewFromWizard(wizard),
                onChange = viewModel::onWizardChanged,
                onApply = viewModel::applyWizard,
                onDismiss = viewModel::dismissWizard,
            )
        }
    }
}
