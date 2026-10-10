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
import com.lethio.macros.domain.nutrition.GoalTargetLimits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    viewModel: GoalsViewModel = hiltViewModel(),
    onSavedGoals: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val massUnit = LocalMassUnit.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.daily_goals)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scrollable, so large text never cuts off Save.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // The calculator comes before the fields: whoever is unsure is likeliest to guess too low.
            OutlinedButton(
                onClick = { viewModel.openWizard(massUnit) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.help_me_pick))
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.goals_new_note))
            GoalFields(state) { field, value ->
                when (field) {
                    GoalField.CALORIES -> viewModel.onCaloriesChanged(value)
                    GoalField.PROTEIN -> viewModel.onProteinChanged(value)
                    GoalField.FAT -> viewModel.onFatChanged(value)
                    GoalField.CARBS -> viewModel.onCarbsChanged(value)
                }
            }
            if (state.failed) Text(stringResource(R.string.diary_edit_failed), color = MaterialTheme.colorScheme.error)
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
            ) {
                Text(stringResource(R.string.save_goals))
            }
            onSavedGoals?.let { navigate ->
                OutlinedButton(onClick = navigate, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.saved_goals))
                }
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

@Composable
internal fun GoalFields(state: GoalsUiState, onChange: (GoalField, String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })
    OutlinedTextField(
        value = state.calories,
        onValueChange = { onChange(GoalField.CALORIES, it) },
        label = { Text(stringResource(R.string.calories_kcal)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = imeActions,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !state.saving,
        isError = GoalField.CALORIES in state.invalidFields || GoalField.CALORIES in state.aboveMaxFields || state.caloriesUnsafelyLow,
        // Errors sit under their own field. The unsafe minimum is here too, as the only message
        // that stops a save.
        supportingText = if (GoalField.CALORIES in state.aboveMaxFields) {
            { Text(stringResource(R.string.target_max, GoalTargetLimits.MAX_CALORIES.toInt().toString())) }
        } else if (GoalField.CALORIES in state.invalidFields) {
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
        onValueChange = { onChange(GoalField.PROTEIN, it) },
        label = { Text(stringResource(R.string.protein_g)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = imeActions,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !state.saving,
        isError = GoalField.PROTEIN in state.invalidFields || GoalField.PROTEIN in state.aboveMaxFields,
        supportingText = if (GoalField.PROTEIN in state.aboveMaxFields) {
            { Text(stringResource(R.string.target_max, GoalTargetLimits.MAX_GRAMS.toInt().toString())) }
        } else if (GoalField.PROTEIN in state.invalidFields) {
            { Text(stringResource(R.string.value_must_be_zero_or_more)) }
        } else {
            null
        },
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.fat,
        onValueChange = { onChange(GoalField.FAT, it) },
        label = { Text(stringResource(R.string.fat_g)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = imeActions,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !state.saving,
        isError = GoalField.FAT in state.invalidFields || GoalField.FAT in state.aboveMaxFields,
        supportingText = if (GoalField.FAT in state.aboveMaxFields) {
            { Text(stringResource(R.string.target_max, GoalTargetLimits.MAX_GRAMS.toInt().toString())) }
        } else if (GoalField.FAT in state.invalidFields) {
            { Text(stringResource(R.string.value_must_be_zero_or_more)) }
        } else {
            null
        },
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.carbs,
        onValueChange = { onChange(GoalField.CARBS, it) },
        label = { Text(stringResource(R.string.carbs_g)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = imeActions,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !state.saving,
        isError = GoalField.CARBS in state.invalidFields || GoalField.CARBS in state.aboveMaxFields,
        supportingText = if (GoalField.CARBS in state.aboveMaxFields) {
            { Text(stringResource(R.string.target_max, GoalTargetLimits.MAX_GRAMS.toInt().toString())) }
        } else if (GoalField.CARBS in state.invalidFields) {
            { Text(stringResource(R.string.value_must_be_zero_or_more)) }
        } else {
            null
        },
    )
    Spacer(Modifier.height(16.dp))
    // Cross-field advisories; they never block saving.
    if (state.caloriesBelowFloor) {
        // Uses the reader's own floor once the calculator has run.
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

}
