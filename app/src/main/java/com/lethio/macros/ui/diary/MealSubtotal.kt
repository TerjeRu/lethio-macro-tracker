package com.lethio.macros.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lethio.macros.R
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.ui.util.EnergyFormat
import com.lethio.macros.ui.util.LocalEnergyUnit
import com.lethio.macros.ui.util.nutritionNumber

/** Sum immutable entry snapshots before conversion or display rounding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MealSubtotal(entries: List<LogEntry>) {
    if (entries.isEmpty()) return
    val total = Macros.sum(entries.map { it.macros })
    val locale = LocalConfiguration.current.locales[0]
    val grams = stringResource(R.string.grams_short)
    val values = listOf(
        "${nutritionNumber(total.calories * LocalEnergyUnit.current.perKcal, 0, locale)} ${EnergyFormat.label()}",
        "${stringResource(R.string.protein)} ${nutritionNumber(total.proteinG, 1, locale)} $grams",
        "${stringResource(R.string.carbs)} ${nutritionNumber(total.carbsG, 1, locale)} $grams",
        "${stringResource(R.string.fat)} ${nutritionNumber(total.fatG, 1, locale)} $grams",
    )
    FlowRow(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        values.forEachIndexed { index, value ->
            Text(
                text = if (index == 0) value else "\u00B7 $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
