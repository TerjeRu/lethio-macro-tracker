package com.lethio.macros.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lethio.macros.R
import com.lethio.macros.domain.model.ActivityLevel
import com.lethio.macros.domain.model.GoalDirection
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Sex
import com.lethio.macros.ui.util.labelRes
import com.lethio.macros.ui.util.rateLabelRes
import com.lethio.macros.ui.util.weightLabelRes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalWizardSheet(
    wizard: WizardState,
    preview: Macros?,
    onChange: (WizardState.() -> WizardState) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.goal_wizard_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.goal_wizard_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (wizard.massUnit.usesStone) {
                CompoundField(
                    majorValue = wizard.weightStone,
                    onMajorChange = { v -> onChange { copy(weightStone = v) } },
                    majorLabel = stringResource(R.string.weight_stone),
                    minorValue = wizard.weightPounds,
                    onMinorChange = { v -> onChange { copy(weightPounds = v) } },
                    minorLabel = stringResource(R.string.weight_pounds),
                )
            } else {
                NumberField(
                    value = wizard.weight,
                    onValueChange = { v -> onChange { copy(weight = v) } },
                    label = stringResource(wizard.massUnit.weightLabelRes),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (wizard.massUnit.usesFeetInches) {
                CompoundField(
                    majorValue = wizard.heightFeet,
                    onMajorChange = { v -> onChange { copy(heightFeet = v) } },
                    majorLabel = stringResource(R.string.height_feet),
                    minorValue = wizard.heightInches,
                    onMinorChange = { v -> onChange { copy(heightInches = v) } },
                    minorLabel = stringResource(R.string.height_inches),
                )
            } else {
                NumberField(
                    value = wizard.heightCm,
                    onValueChange = { v -> onChange { copy(heightCm = v) } },
                    label = stringResource(R.string.height_cm),
                )
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                value = wizard.ageYears,
                onValueChange = { v -> onChange { copy(ageYears = v) } },
                label = stringResource(R.string.age_years),
                decimal = false,
                imeAction = ImeAction.Done,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.sex_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { sex ->
                    FilterChip(
                        selected = wizard.sex == sex,
                        onClick = { onChange { copy(sex = sex) } },
                        label = { Text(stringResource(sex.labelRes)) },
                        enabled = !wizard.usesLeanMass,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (wizard.usesLeanMass) R.string.sex_unused else R.string.sex_explainer,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            NumberField(
                value = wizard.bodyFatPercent,
                onValueChange = { v -> onChange { copy(bodyFatPercent = v) } },
                label = stringResource(R.string.body_fat_percent),
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.body_fat_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.activity_level), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityLevel.entries.forEach { level ->
                    FilterChip(
                        selected = wizard.activityLevel == level,
                        onClick = { onChange { copy(activityLevel = level) } },
                        label = { Text(stringResource(level.labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.goal_direction), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalDirection.entries.forEach { direction ->
                    FilterChip(
                        selected = wizard.direction == direction,
                        onClick = { onChange { copy(direction = direction) } },
                        label = { Text(stringResource(direction.labelRes)) },
                    )
                }
            }

            if (wizard.needsRate) {
                Spacer(Modifier.height(12.dp))
                NumberField(
                    value = wizard.rate,
                    onValueChange = { v -> onChange { copy(rate = v) } },
                    label = stringResource(wizard.massUnit.rateLabelRes),
                    imeAction = ImeAction.Done,
                )
            }

            Spacer(Modifier.height(20.dp))
            if (preview != null) {
                Text(
                    stringResource(R.string.goal_wizard_result, preview.calories.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))

                Text(
                    stringResource(
                        if (wizard.usesLeanMass) {
                            R.string.goal_wizard_estimate_katch
                        } else {
                            R.string.goal_wizard_estimate_mifflin
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
                enabled = preview != null,
            ) {
                Text(stringResource(R.string.use_these_targets))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun CompoundField(
    majorValue: String,
    onMajorChange: (String) -> Unit,
    majorLabel: String,
    minorValue: String,
    onMinorChange: (String) -> Unit,
    minorLabel: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            value = majorValue,
            onValueChange = onMajorChange,
            label = majorLabel,
            decimal = false,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = minorValue,
            onValueChange = onMinorChange,
            label = minorLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    decimal: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
        modifier = modifier,
        singleLine = true,
    )
}
