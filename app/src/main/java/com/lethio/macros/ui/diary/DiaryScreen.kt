package com.lethio.macros.ui.diary

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.lethio.macros.domain.model.Macros
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Add
import com.lethio.macros.ui.util.appDateFormatter
import androidx.compose.ui.text.style.TextAlign
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.ui.util.macroPart
import com.lethio.macros.domain.model.calculationCreditsFor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import com.lethio.macros.ui.util.DateRefreshEffect
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * One day of the diary. Chevrons and swipes move between days, the date opens a calendar, and each
 * meal adds straight to itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
    onNavigateToHistory: ((LocalDate) -> Unit)? = null,
    onEditGoals: ((LocalDate) -> Unit)? = null,
    onAddToMeal: ((MealType) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    val lastCopied by viewModel.lastCopied.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val actionBusy by viewModel.actionBusy.collectAsStateWithLifecycle()
    val actionFailed by viewModel.actionFailed.collectAsStateWithLifecycle()
    val clearDate by viewModel.clearDate.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    DateRefreshEffect(viewModel::refreshDate, viewModel::dateRefreshDelayMillis)

    // Undo rather than a confirmation dialog: faster, and safer for the mistake.
    val undoLabel = stringResource(R.string.undo)
    val deletedMessage = stringResource(if (lastDeleted?.wholeDay == true) R.string.entries_cleared else R.string.entry_deleted)
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

    // A copied meal can be taken back the same way a deletion can.
    val copiedMessage = lastCopied?.size?.let { pluralStringResource(R.plurals.entries_copied, it, it) }
    LaunchedEffect(lastCopied) {
        if (lastCopied != null && copiedMessage != null) {
            val result = snackbarHostState.showSnackbar(message = copiedMessage, actionLabel = undoLabel)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoCopy() else viewModel.clearCopyUndo()
        }
    }

    val dateFormatter = appDateFormatter()
    Scaffold(
        topBar = {
            DiaryDateHeader(state.date, state.isToday, viewModel::goToPreviousDay,
                viewModel::goToNextDay, { showDatePicker = true }, viewModel::goToToday,
                onNavigateToSettings, onNavigateToHistory?.let { { it(state.date) } },
                onEditGoals?.let { { it(state.date) } }, viewModel::requestClearDay,
                !actionBusy, state.day.entriesByMeal.values.any { it.isNotEmpty() })
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
            if (actionFailed && clearDate == null) item {
                Text(stringResource(R.string.entries_change_failed), color = MaterialTheme.colorScheme.error)
                if (lastDeleted != null) TextButton(onClick = viewModel::undoDelete, enabled = !actionBusy) {
                    Text(undoLabel)
                }
            }

            // One card for "today against target", with what is left per macro.
            item { DayProgressCard(totals = state.day.totals, targets = state.goals.targets) }

            val dayIsEmpty = MealType.ordered.all { state.day.entriesByMeal[it].isNullOrEmpty() }
            if (dayIsEmpty) item {
                Text(
                    stringResource(R.string.nothing_logged_this_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Every meal is a header row with its energy subtotal and an add button, so a
            // food can go straight into a meal; an empty meal is just that one line.
            MealType.ordered.forEach { mealType ->
                val entries = state.day.entriesByMeal[mealType].orEmpty()
                item(key = "meal-${mealType.name}") {
                    MealHeader(mealType, entries, onAddToMeal?.let { { it(mealType) } },
                        onCopy = if (state.previousDay.entriesByMeal[mealType].isNullOrEmpty() || actionBusy) null
                        else { { viewModel.copyMealFromPreviousDay(mealType) } },
                        copyLabel = stringResource(if (state.isToday) R.string.copy_from_yesterday else R.string.copy_from_previous_day))
                }
                // The full macro subtotal only when it adds something: one entry already shows it.
                if (entries.size > 1) item(key = "subtotal-${mealType.name}") { MealSubtotal(entries) }
                items(entries, key = { it.id }) { entry ->
                    LogItemRow(entry = entry, onDelete = { viewModel.deleteLog(entry) }, onEdit = { viewModel.editEntry(entry) })
                }
            }

            // A publisher's calculation credit, only when a shown entry used its data.
            val credits = calculationCreditsFor(state.day.entriesByMeal.values.flatten().map { it.macros })
            if (credits.isNotEmpty()) item { CalculationCredits(credits) }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    editing?.let { draft ->
        EntryEditSheet(draft, viewModel::editAmount, viewModel::editMeal, viewModel::cancelEdit, viewModel::saveEdit, today,
            onDelete = { viewModel.cancelEdit(); viewModel.deleteLog(draft.original) })
    }
    clearDate?.let { date ->
        AlertDialog(onDismissRequest = viewModel::dismissClearDay,
            title = { Text(stringResource(R.string.clear_entries_title, date.format(dateFormatter))) },
            text = {
                Column {
                    Text(stringResource(R.string.clear_entries_note))
                    if (actionFailed) Text(stringResource(R.string.entries_change_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearDay, enabled = !actionBusy) { Text(stringResource(R.string.clear_entries)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearDay, enabled = !actionBusy) { Text(stringResource(R.string.cancel)) }
            })
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

/**
 * Publisher wording owed on figures calculated from its data. Licence text, so it is
 * shown as written and not translated; see [com.lethio.macros.domain.model.DataSourceAttribution].
 */
@Composable
internal fun CalculationCredits(credits: List<String>) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        credits.forEach { credit ->
            Text(
                text = credit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Today against target in one card. Calories lead, then each macro with what is left --
 * the number a reader acts on mid-day. Going over is stated in the same neutral colour, never red.
 */
@Composable
internal fun DayProgressCard(totals: Macros, targets: Macros) {
    val consumed = totals.calories
    val target = targets.calories
    val progress = if (target > 0) (consumed / target).toFloat().coerceIn(0f, 1f) else 0f
    // Honours Settings > Accessibility > Remove animations.
    val animate = animationsEnabled()
    val tweened by animateFloatAsState(targetValue = progress, label = "calories")
    val animatedProgress = if (animate) tweened else progress
    val remaining = target - consumed
    val summary = stringResource(R.string.calorie_summary_cd, consumed.roundToInt(), target.roundToInt(),
        remaining.coerceAtLeast(0.0).roundToInt())
    val macros = MaterialTheme.macroColors
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
                // One wrapping line, so at large text it flows instead of squeezing into a column.
                val ofTarget = " " + stringResource(R.string.of_kcal, EnergyFormat.value(target), EnergyFormat.label())
                val headline = MaterialTheme.typography.displaySmall.toSpanStyle()
                    .copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(buildAnnotatedString {
                    withStyle(headline) { append(EnergyFormat.value(consumed).toString()) }
                    append(ofTarget)
                }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (remaining >= 0) stringResource(R.string.kcal_remaining, EnergyFormat.value(remaining))
                    else stringResource(R.string.kcal_over, EnergyFormat.value(-remaining)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            MacroRow(stringResource(R.string.protein), totals.proteinG, targets.proteinG, macros.protein, macros.proteinContainer)
            MacroRow(stringResource(R.string.fat), totals.fatG, targets.fatG, macros.fat, macros.fatContainer)
            MacroRow(stringResource(R.string.carbs), totals.carbsG, targets.carbsG, macros.carbs, macros.carbsContainer)
        }
    }
}

/**
 * One macro's progress row. Internal so `MacroRowSemanticsTest` can test its semantics directly;
 * without them the bars read as unlabelled progress indicators.
 */
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
    val leftNow = (target - current).roundToInt()
    // The spoken state leads with what is left (or over), as the visible row does.
    val stateText = (if (leftNow >= 0) stringResource(R.string.macro_left, leftNow.toString())
        else stringResource(R.string.macro_over, (-leftNow).toString())) + ", " +
        stringResource(R.string.macro_progress_cd, current.roundToInt(), target.roundToInt())

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
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            // What is left is the actionable number; over is stated, neutrally.
            val left = (target - current).roundToInt()
            Text(
                text = if (left >= 0) stringResource(R.string.macro_left, left.toString())
                else stringResource(R.string.macro_over, (-left).toString()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
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
        Text(
            text = "${current.roundToInt()} / ${target.roundToInt()} $unit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Diary header's date -- weekday, day and month in the reader's language, with the
 * year only outside the current year. One function so the header and its tests cannot drift.
 */
internal fun diaryHeaderDate(date: LocalDate, locale: java.util.Locale, today: LocalDate = LocalDate.now()): String {
    val skeleton = if (date.year == today.year) "EEEdMMM" else "EEEdMMMyyyy"
    return date.format(DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/** a meal's header line -- name, energy subtotal when it has entries, and add. */
@Composable
private fun MealHeader(
    mealType: MealType,
    entries: List<LogEntry>,
    onAdd: (() -> Unit)?,
    onCopy: (() -> Unit)? = null,
    copyLabel: String = "",
) {
    val label = stringResource(mealType.labelRes)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Lets screen-reader users jump between meals instead of walking every row.
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (entries.isNotEmpty()) Text(
            EnergyFormat.format(Macros.sum(entries.map { it.macros }).calories),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // With something to copy, + offers a choice; otherwise it goes straight to Search.
        if (onAdd != null) Box {
            IconButton(onClick = { if (onCopy != null) menu = true else onAdd() }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_to_meal_cd, label))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.add_food_item)) },
                    onClick = { menu = false; onAdd() })
                if (onCopy != null) DropdownMenuItem(text = { Text(copyLabel) },
                    onClick = { menu = false; onCopy() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogItemRow(entry: LogEntry, onDelete: () -> Unit, onEdit: () -> Unit) {
    val unitLabel = PortionLabels.of(entry.quantity.unit)
    val macroSummary = stringResource(
        R.string.macro_breakdown_cd,
        entry.macros.proteinG.roundToInt(),
        entry.macros.fatG.roundToInt(),
        entry.macros.carbsG.roundToInt(),
    )
    val deleteLabel = stringResource(R.string.delete_cd)
    // Swipe to delete (with Undo). The same action is offered to
    // accessibility services and in the edit sheet, so it never depends on the gesture.
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        },
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.diary_edit_entry), onClick = onEdit)
                    .sizeIn(minHeight = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    // Announced as words rather than as "P30 F12 C45" read literally.
                    text = (macroPart("P", entry.macros, Nutrient.PROTEIN, entry.macros.proteinG, "") +
                        macroPart("F", entry.macros, Nutrient.FAT, entry.macros.fatG, "") +
                        macroPart("C", entry.macros, Nutrient.CARBS, entry.macros.carbsG, "")).trimEnd(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    // Capped so, at large text, the macros wrap instead of squeezing the name.
                    modifier = Modifier.weight(0.5f, fill = false).padding(start = 8.dp)
                        .clearAndSetSemantics { contentDescription = macroSummary },
                )
            }
        }
    }
}

/** All text gets its required height; the date stays visible when the diary scrolls. */
@Composable
internal fun DiaryDateHeader(
    date: LocalDate,
    isToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
    onToday: () -> Unit,
    onSettings: () -> Unit,
    onHistory: (() -> Unit)? = null,
    onEditGoals: (() -> Unit)? = null,
    onClearEntries: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
    canClear: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // One row. The date itself opens the calendar; "Today" appears only away from it;
    // History is an icon beside Settings. The weekday is shown because "8 Oct" alone is hard to place.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateText = diaryHeaderDate(date, locale)
    val pickLabel = stringResource(R.string.jump_to_date_cd)
    Surface {
        // MainActivity's outer scaffold already applies the status-bar inset.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day_cd))
            }
            Column(
                Modifier.weight(1f).clickable(onClickLabel = pickLabel, role = Role.Button, onClick = onPickDate)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(dateText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() })
                if (!isToday) TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.diary_back_to_today))
                }
            }
            IconButton(onClick = onNext, enabled = !isToday) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day_cd))
            }
            if (onHistory != null) IconButton(onClick = onHistory) {
                Icon(Icons.Default.BarChart, stringResource(R.string.diary_history))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, stringResource(R.string.nav_settings))
            }
            if (onEditGoals != null || onClearEntries != null) Box {
                IconButton(onClick = { menuExpanded = true }, enabled = actionsEnabled) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.day_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onEditGoals != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_saved_goals)) },
                        onClick = { menuExpanded = false; onEditGoals() }, enabled = actionsEnabled)
                    if (onClearEntries != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_entries)) },
                        onClick = { menuExpanded = false; onClearEntries() }, enabled = actionsEnabled && canClear)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EntryEditSheet(
    draft: EntryEditState,
    onAmount: (String) -> Unit,
    onMeal: (MealType) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    today: LocalDate,
    onDelete: (() -> Unit)? = null,
) {
    val preview = correctedEntry(draft.original, draft.amount, draft.meal)
    ModalBottomSheet(onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.diary_edit_entry), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(draft.original.foodName)
            val entryDate = draft.original.date.format(appDateFormatter())
            Text(if (draft.original.date == today) "${stringResource(R.string.today_label)} · $entryDate"
                else stringResource(R.string.diary_past_entry_date, entryDate))
            OutlinedTextField(value = draft.amount, onValueChange = onAmount,
                label = { Text(stringResource(R.string.amount)) },
                suffix = { Text(PortionLabels.of(draft.original.quantity.unit)) },
                enabled = !draft.saving && draft.original.quantity.amount > 0,
                isError = preview == null,
                supportingText = { if (preview == null) Text(stringResource(R.string.diary_edit_positive_amount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth())
            if (draft.original.quantity.amount <= 0) Text(stringResource(R.string.diary_edit_amount_unavailable))
            Text(stringResource(R.string.meal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.ordered.forEach { meal ->
                    FilterChip(selected = draft.meal == meal, onClick = { onMeal(meal) },
                        enabled = !draft.saving, label = { Text(stringResource(meal.labelRes)) })
                }
            }
            preview?.let {
                Text(EnergyFormat.format(it.macros.calories))
                Text(stringResource(R.string.macro_breakdown_cd, it.macros.proteinG.roundToInt(),
                    it.macros.fatG.roundToInt(), it.macros.carbsG.roundToInt()))
            }
            if (draft.failed) Text(stringResource(R.string.diary_edit_failed),
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) TextButton(onClick = onDelete, enabled = !draft.saving) {
                    Text(stringResource(R.string.delete_cd), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onCancel, enabled = !draft.saving) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onSave, enabled = !draft.saving && preview != null) { Text(stringResource(R.string.diary_save_changes)) }
            }
        }
    }
}
