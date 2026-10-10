package com.lethio.macros.ui.goals

import com.lethio.macros.ui.util.appDateFormatter
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.ui.util.EnergyFormat
import com.lethio.macros.ui.util.nutritionNumber
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun goalRange(period: SavedGoalPeriod): String {
    val formatter = appDateFormatter()
    val from = period.goals.effectiveFrom.format(formatter)
    return period.nextDate?.let {
        stringResource(R.string.goals_range, from, it.minusDays(1).format(formatter))
    } ?: stringResource(R.string.goals_from, from)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedGoalsScreen(onNavigateBack: () -> Unit, viewModel: SavedGoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler {
        if (!state.deletingBusy && state.editing?.fields?.saving != true) onNavigateBack()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.saved_goals)) }, navigationIcon = {
            IconButton(onClick = onNavigateBack, enabled = !state.deletingBusy && state.editing?.fields?.saving != true) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_cd))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { CircularProgressIndicator() }
            if (state.loadFailed) item {
                Text(stringResource(R.string.goals_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.diary_history_retry)) }
            }
            if (!state.loading && !state.loadFailed && (state.periods.isEmpty() ||
                viewModel.requestedDate?.let { date -> state.periods.none { it.goals.effectiveFrom <= date } } == true)) {
                item { Text(stringResource(R.string.no_saved_goals)) }
            }
            items(state.periods, key = { it.goals.effectiveFrom.toString() }) { period ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(goalRange(period), style = MaterialTheme.typography.titleMedium)
                        Text(EnergyFormat.format(period.goals.targets.calories))
                        // The existing macro labels convey units; no rounded value is used for saving.
                        Text("${stringResource(R.string.protein_g)}: ${nutritionNumber(period.goals.targets.proteinG, 2, Locale.getDefault())}")
                        Text("${stringResource(R.string.carbs_g)}: ${nutritionNumber(period.goals.targets.carbsG, 2, Locale.getDefault())}")
                        Text("${stringResource(R.string.fat_g)}: ${nutritionNumber(period.goals.targets.fatG, 2, Locale.getDefault())}")
                        Row {
                            TextButton(onClick = { viewModel.edit(period) }) { Text(stringResource(R.string.edit)) }
                            TextButton(onClick = { viewModel.requestDelete(period) }) { Text(stringResource(R.string.delete_cd)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    state.editing?.let { edit ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissEdit,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edit_saved_goals), style = MaterialTheme.typography.headlineSmall)
                Text(goalRange(edit.period), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.goals_range_note))
                GoalFields(edit.fields, viewModel::change)
                if (edit.fields.failed) Text(stringResource(R.string.goals_change_failed), color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = viewModel::dismissEdit, enabled = !edit.fields.saving) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = viewModel::save, enabled = edit.fields.canSave) { Text(stringResource(R.string.diary_save_changes)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    state.deleting?.let { period ->
        AlertDialog(onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.delete_goals_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(goalRange(period))
                    Text(stringResource(R.string.delete_goals_note))
                    if (state.deleteFailed) Text(stringResource(R.string.goals_change_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::delete, enabled = !state.deletingBusy) { Text(stringResource(R.string.delete_cd)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete, enabled = !state.deletingBusy) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
