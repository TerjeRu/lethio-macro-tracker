package com.lethio.macros.ui.diary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.ui.components.EmptyState
import com.lethio.macros.ui.theme.macroColors
import com.lethio.macros.ui.util.EnergyFormat
import com.lethio.macros.ui.util.animationsEnabled
import com.lethio.macros.ui.util.PortionLabels
import com.lethio.macros.ui.util.formatQuantity
import com.lethio.macros.ui.util.labelRes
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshDate()
    }

    val undoLabel = stringResource(R.string.undo)
    val deletedMessage = stringResource(R.string.entry_deleted)
    LaunchedEffect(lastDeleted) {
        if (lastDeleted != null) {
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.clearUndo()
            }
        }
    }

    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val todayLabel = stringResource(R.string.today_label)
    val displayDate = if (state.isToday) todayLabel else state.date.format(dateFormatter)
    val jumpToTodayCd = stringResource(R.string.jump_to_today_cd)

    Scaffold(
        topBar = {

            val barHeight = TopAppBarDefaults.TopAppBarExpandedHeight *
                LocalDensity.current.fontScale.coerceAtLeast(1f)
            TopAppBar(
                expandedHeight = barHeight,
                title = {
                    Text(
                        text = displayDate,

                        maxLines = 2,

                        color = if (state.isToday) {
                            Color.Unspecified
                        } else {
                            MaterialTheme.colorScheme.primary
                        },

                        modifier = if (state.isToday) {
                            Modifier
                        } else {
                            Modifier
                                .sizeIn(minHeight = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .clickable(onClick = viewModel::goToToday)
                                .semantics {
                                    contentDescription = jumpToTodayCd
                                    role = Role.Button
                                }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::goToPreviousDay) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.previous_day_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::goToNextDay, enabled = !state.isToday) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.next_day_cd),
                        )
                    }
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = stringResource(R.string.jump_to_date_cd),
                        )
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                CalorieSummaryCard(
                    consumed = state.day.totals.calories,
                    target = state.goals.targets.calories,
                )
            }

            item {
                MacroBarsCard(
                    proteinG = state.day.totals.proteinG,
                    proteinTargetG = state.goals.targets.proteinG,
                    fatG = state.day.totals.fatG,
                    fatTargetG = state.goals.targets.fatG,
                    carbsG = state.day.totals.carbsG,
                    carbsTargetG = state.goals.targets.carbsG,
                )
            }

            val dayIsEmpty = MealType.ordered.all { state.day.entriesByMeal[it].isNullOrEmpty() }
            if (dayIsEmpty) {
                item {
                    EmptyState(
                        message = stringResource(R.string.nothing_logged_this_day),
                        icon = Icons.Default.RestaurantMenu,
                    )
                }
            } else {
                MealType.ordered.forEach { mealType ->
                    val entries = state.day.entriesByMeal[mealType].orEmpty()
                    item {
                        Text(
                            text = stringResource(mealType.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,

                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { heading() },
                        )
                    }
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_items_logged),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(entries, key = { it.id }) { entry ->
                            LogItemRow(entry = entry, onDelete = { viewModel.deleteLog(entry) })
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showDatePicker) {
        ModalBottomSheet(onDismissRequest = { showDatePicker = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.jump_to_date_cd),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.loggedDates.isEmpty()) {
                    EmptyState(
                        message = stringResource(R.string.no_history),
                        icon = Icons.Default.EventBusy,
                    )
                } else {
                    state.loggedDates.sortedDescending().forEach { date ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.goToDate(date)
                                    showDatePicker = false
                                }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                        ) {
                            Text(date.format(dateFormatter))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CalorieSummaryCard(consumed: Double, target: Double) {
    val progress = if (target > 0) (consumed / target).toFloat().coerceIn(0f, 1.5f) else 0f
    val remaining = (target - consumed).coerceAtLeast(0.0)

    val animate = animationsEnabled()
    val tweened by animateFloatAsState(
        targetValue = progress.coerceAtMost(1f),
        label = "calories",
    )
    val animatedProgress = if (animate) tweened else progress.coerceAtMost(1f)

    val summary = stringResource(
        R.string.calorie_summary_cd,
        consumed.roundToInt(),
        target.roundToInt(),
        remaining.roundToInt(),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)

                .clearAndSetSemantics { contentDescription = summary },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = EnergyFormat.value(consumed).toString(),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.of_kcal, EnergyFormat.value(target), EnergyFormat.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.kcal_remaining, EnergyFormat.value(remaining)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun MacroBarsCard(
    proteinG: Double,
    proteinTargetG: Double,
    fatG: Double,
    fatTargetG: Double,
    carbsG: Double,
    carbsTargetG: Double,
) {
    val macros = MaterialTheme.macroColors
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MacroRow(
                label = stringResource(R.string.protein),
                current = proteinG,
                target = proteinTargetG,
                color = macros.protein,
                trackColor = macros.proteinContainer,
            )
            MacroRow(
                label = stringResource(R.string.fat),
                current = fatG,
                target = fatTargetG,
                color = macros.fat,
                trackColor = macros.fatContainer,
            )
            MacroRow(
                label = stringResource(R.string.carbs),
                current = carbsG,
                target = carbsTargetG,
                color = macros.carbs,
                trackColor = macros.carbsContainer,
            )
        }
    }
}

@Composable
internal fun MacroRow(
    label: String,
    current: Double,
    target: Double,
    color: Color,
    trackColor: Color,
) {
    val progress = if (target > 0) (current / target).toFloat().coerceIn(0f, 1f) else 0f
    val animate = animationsEnabled()
    val tweened by animateFloatAsState(targetValue = progress, label = label)
    val animatedProgress = if (animate) tweened else progress
    val unit = stringResource(R.string.grams_short)
    val stateText = stringResource(
        R.string.macro_progress_cd,
        current.roundToInt(),
        target.roundToInt(),
    )

    Column(

        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = stateText
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${current.roundToInt()}$unit / ${target.roundToInt()}$unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
private fun LogItemRow(entry: LogEntry, onDelete: () -> Unit) {
    val unitLabel = PortionLabels.of(entry.quantity.unit)
    val macroSummary = stringResource(
        R.string.macro_breakdown_cd,
        entry.macros.proteinG.roundToInt(),
        entry.macros.fatG.roundToInt(),
        entry.macros.carbsG.roundToInt(),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.foodName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${entry.quantity.amount.formatQuantity()} $unitLabel · " +
                        EnergyFormat.format(entry.macros.calories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(

                text = "P${entry.macros.proteinG.roundToInt()} " +
                    "F${entry.macros.fatG.roundToInt()} " +
                    "C${entry.macros.carbsG.roundToInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = macroSummary },
            )

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_cd),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
