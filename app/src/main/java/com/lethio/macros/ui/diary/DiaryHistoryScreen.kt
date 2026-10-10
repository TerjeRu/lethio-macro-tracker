package com.lethio.macros.ui.diary

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.lethio.macros.domain.model.calculationCreditsFor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.domain.model.DiaryHistoryDay
import com.lethio.macros.domain.model.DiaryHistoryPeriod
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.ui.util.DateRefreshEffect
import com.lethio.macros.ui.util.LocalEnergyUnit
import com.lethio.macros.ui.util.nutritionNumber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DiaryHistoryScreen(
    onNavigateBack: () -> Unit,
    onOpenDate: (LocalDate) -> Unit,
    viewModel: DiaryHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DateRefreshEffect(viewModel::refreshDate, viewModel::dateRefreshDelayMillis)
    DiaryHistoryContent(state, onNavigateBack, onOpenDate, viewModel::selectPeriod,
        viewModel::selectMetric, viewModel::retry)
}

/** Exact text rows are the day controls; the chart needs no gestures or tiny touch targets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiaryHistoryContent(
    state: DiaryHistoryUiState,
    onNavigateBack: () -> Unit,
    onOpenDate: (LocalDate) -> Unit,
    onPeriod: (DiaryHistoryPeriod) -> Unit,
    onMetric: (DiaryHistoryMetric) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0])
    val range = "${state.window.start.format(dateFormat)} – ${state.window.end.format(dateFormat)}"
    Scaffold(topBar = {
        Surface {
            // The enclosing application scaffold owns the system-bar inset.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_cd))
                    }
                    Text(stringResource(R.string.diary_history), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                }
                Text(range, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.diary_history_intake),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryHistoryPeriod.entries.forEach { period ->
                            FilterChip(selected = state.window.period == period, onClick = { onPeriod(period) },
                                label = { Text(stringResource(if (period == DiaryHistoryPeriod.SEVEN_DAYS)
                                    R.string.diary_history_7_days else R.string.diary_history_28_days)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryHistoryMetric.entries.forEach { metric ->
                            FilterChip(selected = state.metric == metric, onClick = { onMetric(metric) },
                                label = { Text(metricLabel(metric)) })
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") { Text(stringResource(R.string.loading)) }
                state.failed -> item(key = "failure") {
                    Column {
                        Text(stringResource(R.string.diary_history_failed))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.diary_history_retry)) }
                    }
                }
                else -> {
                    historySummary(state.days, state.metric)?.let { summary ->
                        item(key = "summary") { DiaryHistorySummaryLine(summary, state.metric) }
                    }
                    item(key = "chart") { DiaryHistoryChart(state, range, onOpenDate) }
                    items(state.days, key = { it.date.toString() }) { day ->
                        DiaryHistoryDayRow(day, onOpenDate)
                    }
                    // Credit any publisher whose data fed a total shown in this window.
                    val credits = calculationCreditsFor(state.days.flatMap { it.datasets }.toSet())
                    if (credits.isNotEmpty()) item(key = "credits") { CalculationCredits(credits) }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * What the period amounts to for [metric]. Only logged days count -- an unlogged day is
 * unknown, not zero -- and the line says how many that was. The target is named only when every
 * logged day had the same saved one; otherwise the chart's line shows how it changed. Null when
 * nothing was logged.
 */
internal data class HistorySummary(val loggedDays: Int, val totalDays: Int, val average: Double, val target: Double?)

internal fun historySummary(days: List<DiaryHistoryDay>, metric: DiaryHistoryMetric): HistorySummary? {
    val logged = days.filter { it.loggedIntake != null }
    val values = logged.mapNotNull { day -> day.loggedIntake?.let(metric::value)?.takeIf { it.isFinite() } }
    if (values.isEmpty()) return null
    val targets = logged.map { day -> day.savedGoal?.targets?.let(metric::value) }.distinct()
    return HistorySummary(logged.size, days.size, values.average(), targets.singleOrNull())
}

@Composable
private fun DiaryHistorySummaryLine(summary: HistorySummary, metric: DiaryHistoryMetric) {
    val factor = if (metric == DiaryHistoryMetric.ENERGY) LocalEnergyUnit.current.perKcal else 1.0
    val locale = LocalConfiguration.current.locales[0]
    val unit = metricUnit(metric)
    fun amount(value: Double) = "${historyNumber(value * factor, metric, locale)} $unit"
    var text = stringResource(R.string.diary_history_summary, summary.loggedDays, summary.totalDays,
        amount(summary.average))
    summary.target?.let { text += " · " + stringResource(R.string.diary_history_summary_target, amount(it)) }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** Unrounded raw scale; include zero and every intake and saved target, even above targets. */
internal fun historyChartBounds(days: List<DiaryHistoryDay>, metric: DiaryHistoryMetric): Pair<Double, Double> {
    val values = days.flatMap { day ->
        listOfNotNull(day.loggedIntake?.let(metric::value), day.savedGoal?.targets?.let(metric::value))
    }.filter { it.isFinite() }
    val low = minOf(0.0, values.minOrNull() ?: 0.0)
    val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
    return low to if (low == high) 1.0 else high
}

@Composable
private fun DiaryHistoryChart(state: DiaryHistoryUiState, range: String, onOpenDate: (LocalDate) -> Unit) {
    val metric = state.metric
    val unit = metricUnit(metric)
    val label = metricLabel(metric)
    val (low, high) = historyChartBounds(state.days, metric)
    // Tapping a bar shows its day's value; tapping the same bar again opens the day.
    // The rows below stay the accessible controls, so the chart keeps its single summary node.
    var selected by remember(state.window) { mutableStateOf<Int?>(null) }
    val factor = if (metric == DiaryHistoryMetric.ENERGY) LocalEnergyUnit.current.perKcal else 1.0
    val intakeColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.onSurface
    val baselineColor = MaterialTheme.colorScheme.outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
    if (state.days.none { it.loggedIntake != null || it.savedGoal != null }) {
        val empty = stringResource(R.string.diary_history_no_entries)
        Card(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label · $unit · $range · $empty" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$label · $unit", style = MaterialTheme.typography.titleSmall)
                Text(empty)
            }
        }
        return
    }
    Card(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label · $unit · $range" }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label · $unit", style = MaterialTheme.typography.titleSmall)
            if (state.days.any { it.savedGoal != null }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(width = 28.dp, height = 8.dp)) {
                        drawLine(targetColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
                    }
                    Text(stringResource(R.string.daily_target), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            val selectedDay = selected?.let(state.days::getOrNull)
            Text(
                selectedDay?.let { day ->
                    day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) + " · " +
                        (day.loggedIntake?.let { "${historyNumber(metric.value(it) * factor, metric, locale)} $unit" }
                            ?: stringResource(R.string.diary_history_no_entries))
                } ?: historyNumber(high * factor, metric, locale),
                style = if (selectedDay != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            )
            Canvas(Modifier.fillMaxWidth().height(144.dp).pointerInput(state.days) {
                detectTapGestures { offset ->
                    val step = size.width.toFloat() / state.window.period.days
                    val column = (offset.x / step).toInt().coerceIn(0, state.days.size - 1)
                    val index = if (rtl) state.days.size - 1 - column else column
                    if (selected == index) onOpenDate(state.days[index].date) else selected = index
                }
            }) {
                val inset = 4.dp.toPx()
                fun y(value: Double) = inset + ((high - value) / (high - low) * (size.height - inset * 2)).toFloat()
                val zero = y(0.0)
                drawLine(baselineColor, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
                val step = size.width / state.window.period.days
                state.days.forEachIndexed { index, day ->
                    val x = if (rtl) size.width - (index + .5f) * step else (index + .5f) * step
                    val width = step * .62f
                    val barColor = if (selected == null || selected == index) intakeColor else intakeColor.copy(alpha = .35f)
                    day.loggedIntake?.let {
                        val value = metric.value(it)
                        if (value.isFinite()) {
                            if (value == 0.0) drawCircle(barColor, minOf(3.dp.toPx(), width / 2), Offset(x, zero))
                            else drawRect(barColor, Offset(x - width / 2, minOf(y(value), zero)),
                                Size(width, kotlin.math.abs(y(value) - zero)))
                        }
                    }
                    day.savedGoal?.targets?.let {
                        val value = metric.value(it)
                        if (value.isFinite()) drawLine(targetColor, Offset(x - step * .44f, y(value)),
                            Offset(x + step * .44f, y(value)), strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())))
                    }
                }
            }
            Text(historyNumber(low * factor, metric, locale), style = MaterialTheme.typography.bodySmall)
            if (state.window.period == DiaryHistoryPeriod.SEVEN_DAYS) {
                // One short weekday under each bar, in the same direction as the bars.
                val weekday = DateTimeFormatter.ofPattern("EEE", locale)
                Row(Modifier.fillMaxWidth()) {
                    state.days.forEach { day ->
                        Text(day.date.format(weekday), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.window.start.format(dateFormat), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                Text(state.window.end.format(dateFormat), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiaryHistoryDayRow(day: DiaryHistoryDay, onOpenDate: (LocalDate) -> Unit) {
    val date = day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0]))
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenDate(day.date) }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            // At large text, wrapping labels and values is more useful than compressed columns.
            val columnsFit = maxWidth >= 560.dp * LocalDensity.current.fontScale
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (day.loggedIntake == null) {
                    Text(date, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.diary_history_no_entries))
                } else if (columnsFit) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(date, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
                        DiaryHistoryMetric.entries.forEach { item ->
                            Column(Modifier.weight(1f)) {
                                Text(metricLabel(item), style = MaterialTheme.typography.labelMedium)
                                Text(metricValue(day.loggedIntake, item), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
                    Text(date, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryHistoryMetric.entries.forEach { item ->
                            Column {
                                Text(metricLabel(item), style = MaterialTheme.typography.labelMedium)
                                Text(metricValue(day.loggedIntake, item), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun metricLabel(metric: DiaryHistoryMetric) = stringResource(when (metric) {
    DiaryHistoryMetric.ENERGY -> R.string.diary_history_energy
    DiaryHistoryMetric.PROTEIN -> R.string.protein
    DiaryHistoryMetric.CARBS -> R.string.carbs
    DiaryHistoryMetric.FAT -> R.string.fat
})

@Composable
private fun metricUnit(metric: DiaryHistoryMetric) = stringResource(if (metric == DiaryHistoryMetric.ENERGY) {
    if (LocalEnergyUnit.current.perKcal == 1.0) R.string.energy_kcal else R.string.energy_kj
} else R.string.grams_short)

@Composable
private fun metricValue(macros: Macros, metric: DiaryHistoryMetric): String {
    val factor = if (metric == DiaryHistoryMetric.ENERGY) LocalEnergyUnit.current.perKcal else 1.0
    return "${historyNumber(metric.value(macros) * factor, metric, LocalConfiguration.current.locales[0])} ${metricUnit(metric)}"
}

/** Display rounding only, without Int saturation for large stored values. */
internal fun historyNumber(value: Double, metric: DiaryHistoryMetric, locale: Locale = Locale.getDefault()): String =
    nutritionNumber(value, if (metric == DiaryHistoryMetric.ENERGY) 0 else 1, locale)
