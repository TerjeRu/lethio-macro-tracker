package com.lethio.macros.ui.product

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

internal const val CONTRIBUTION_CHECKBOX_DEFAULT = false

/**
 * Enters a product for the barcode that just missed, per 100 g, so later scans resolve locally. Quick
 * Add, by contrast, records one meal's totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(
    /** Called with the saved food, so the reader can go straight to logging it. */
    onSaved: (foodId: Long, foodSource: String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AddProductViewModel = hiltViewModel(),
) {
    val proceed: (com.lethio.macros.domain.model.FoodRef) -> Unit = { ref ->
        ref.id?.let { onSaved(it, ref.kind.value) }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contributeEnabled by viewModel.contributeAvailable.collectAsStateWithLifecycle()
    // Next moves down the numeric fields; Done on the last dismisses the keyboard.
    val focusManager = LocalFocusManager.current
    val imeActions = KeyboardActions(onDone = { focusManager.clearFocus() })


    // Publishing each product is its own choice, so the box starts clear.
    var contributeChecked by remember { mutableStateOf(CONTRIBUTION_CHECKBOX_DEFAULT) }
    val contributeOffered = contributeEnabled &&
        viewModel.contributeConfigured &&
        state.offConfirmedMissing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_product)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.barcode.isNotEmpty()) {
                Text(
                    stringResource(R.string.barcode_label, state.barcode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChanged,
                label = { Text(stringResource(R.string.food_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.brand,
                onValueChange = viewModel::onBrandChanged,
                label = { Text(stringResource(R.string.brand_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.per_100g_header),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))

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
                isError = ProductField.CALORIES in state.invalidFields,
                supportingText = if (ProductField.CALORIES in state.invalidFields) {
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
                    isError = ProductField.PROTEIN in state.invalidFields,
                    supportingText = if (ProductField.PROTEIN in state.invalidFields) {
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
                    isError = ProductField.FAT in state.invalidFields,
                    supportingText = if (ProductField.FAT in state.invalidFields) {
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
                    isError = ProductField.CARBS in state.invalidFields,
                    supportingText = if (ProductField.CARBS in state.invalidFields) {
                        { Text(stringResource(R.string.value_must_be_zero_or_more)) }
                    } else {
                        null
                    },
                )
            }

            if (state.energyLooksInconsistent) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.energy_does_not_match_macros, state.suggestedCalories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Offered when the setting is on, the build has a relay, and OFF lacks this barcode.
            if (contributeOffered) {
                Spacer(Modifier.height(16.dp))
                // One toggle for the whole row, so the label is the checkbox's name and is tappable.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = contributeChecked,
                        role = Role.Checkbox,
                        onValueChange = { contributeChecked = it },
                    ),
                ) {
                    Checkbox(
                        checked = contributeChecked,
                        onCheckedChange = null,
                    )
                    Column {
                        Text(stringResource(R.string.contribute_to_off))
                        Text(
                            stringResource(R.string.contribute_to_off_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save(contributeChecked && contributeOffered, proceed) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave && !state.saving,
            ) {
                Text(stringResource(R.string.save_product))
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // The outcome is always shown: a refusal says the food was kept. Dismissing continues to logging.
    val outcome: Pair<Int, Int>? = when (state.contributeState) {
        ContributeState.ACCEPTED ->
            R.string.contribute_sent_title to R.string.contribute_accepted
        ContributeState.REFUSED ->
            R.string.contribute_not_sent_title to R.string.contribute_refused
        ContributeState.UNAVAILABLE ->
            R.string.contribute_unconfirmed_title to R.string.contribute_unavailable
        else -> null
    }
    if (outcome != null) {
        val (title, body) = outcome
        AlertDialog(
            onDismissRequest = { viewModel.proceed(proceed) },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.proceed(proceed) }) {
                    Text(stringResource(R.string.continue_to_log))
                }
            },
        )
    }
}
