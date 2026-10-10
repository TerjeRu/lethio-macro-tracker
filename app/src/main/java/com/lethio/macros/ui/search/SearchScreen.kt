package com.lethio.macros.ui.search

import com.lethio.macros.ui.util.labelRes
import androidx.compose.ui.platform.LocalConfiguration
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.ui.util.macroPart
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.R
import com.lethio.macros.domain.model.DataSource
import com.lethio.macros.domain.model.DataSourceAttribution
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.ui.components.EmptyState
import com.lethio.macros.ui.util.EnergyFormat
import com.lethio.macros.ui.util.PortionLabels
import com.lethio.macros.ui.util.carbLabelSuffix
import com.lethio.macros.ui.util.formatQuantity
import kotlin.math.roundToInt
import java.util.Locale

/**
 * The empty state for a search limited to one table: which table was searched and, when it is in
 * another language, what to type instead.
 */
@Composable
private fun emptyInDatabase(db: SearchedDatabase): String {
    val label = DataSource.fromValue(db.source)
        ?.let(DataSourceAttribution::shortLabelFor)
        ?: db.source
    val first = stringResource(R.string.no_results_in_database, label)
    // The app's chosen language, not the phone's.
    val appLocale = LocalConfiguration.current.locales[0]
    val appLanguage = appLocale.language
    val other = db.languages.firstOrNull { it != appLanguage } ?: return first
    // Named in the reader's language: this is a sentence, not a picker.
    val name = Locale.forLanguageTag(other).getDisplayLanguage(appLocale).ifEmpty { other }
    return first + " " + stringResource(R.string.no_results_in_database_language, label, name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onFoodSelected: (foodId: Long, foodKind: String) -> Unit,
    onAddManually: (query: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    addingTo: com.lethio.macros.domain.model.MealType? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // A new result list starts at the top, not at the previous query's scroll offset.
    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) listState.scrollToItem(0)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Recents come from the log, so re-read them on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshRecents() }

    Scaffold(
        topBar = {
            // No back arrow: Search is a bottom-nav tab.
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.search_foods))
                    // Opened from a meal's add button; the chosen food goes to that meal.
                    addingTo?.let {
                        Text(stringResource(R.string.search_adding_to_meal, stringResource(it.labelRes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.clear_cd),
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            if (state.isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(16.dp),
                )
            }

            // Results are borrowed only when the reader's table had none, so one banner covers
            // the whole list. Above it, so it cannot be scrolled past.
            state.searchedDatabase?.let { db ->
                val borrowed = state.results.isNotEmpty() && state.results.none {
                    it.provenance?.value == db.source
                }
                if (borrowed) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.results_borrowed,
                                DataSource.fromValue(db.source)
                                    ?.let(DataSourceAttribution::shortLabelFor) ?: db.source,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Hoisted: stringResource cannot be called inside the list builder.
            val favoritesTitle = stringResource(R.string.favorites)
            val recentTitle = stringResource(R.string.recent)
            val emptyHint = stringResource(R.string.search_to_get_started)
            val noResults = stringResource(R.string.no_results)

            // Announces arriving results to screen readers; focus stays in the search field.
            // An empty node, since the count is already visible.
            val searchAnnouncement = when {
                state.isSearching -> stringResource(R.string.searching_announcement)
                !state.hasSearched || state.showingShortcuts -> null
                state.results.isEmpty() -> noResults
                else -> pluralStringResource(
                    R.plurals.search_results_count,
                    state.results.size,
                    state.results.size,
                )
            }
            searchAnnouncement?.let { announcement ->
                Box(
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    },
                )
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.showingShortcuts) {
                    // With an empty query, show what the reader has eaten before.
                    shortcutSection(
                        title = favoritesTitle,
                        entries = state.favorites,
                        onSelected = onFoodSelected,
                    )
                    shortcutSection(
                        title = recentTitle,
                        entries = state.recents,
                        onSelected = onFoodSelected,
                    )
                    if (state.favorites.isEmpty() && state.recents.isEmpty()) {
                        item {
                            EmptyState(message = emptyHint, icon = Icons.Default.RestaurantMenu)
                        }
                    }
                } else {
                    items(
                        state.results,
                        key = { "${it.ref.kind.value}_${it.ref.id}" },
                    ) { food ->
                        FoodResultRow(
                            food = food,
                            onClick = {
                                food.ref.id?.let { id -> onFoodSelected(id, food.ref.kind.value) }
                            },
                        )
                        HorizontalDivider()
                    }

                    if (state.results.isEmpty() && state.hasSearched && !state.isSearching) {
                        item {
                            // Says which table was searched, when the search was restricted.
                            EmptyState(
                                message = state.searchedDatabase?.let { db ->
                                    emptyInDatabase(db)
                                } ?: noResults,
                                icon = Icons.Default.SearchOff,
                            )
                            // Manual entry for a food search cannot find, prefilled with the query.
                            if (state.query.isNotBlank()) {
                                TextButton(
                                    onClick = { onAddManually(state.query) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                ) {
                                    Text(stringResource(R.string.add_query_manually, state.query))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodResultRow(food: Food, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = food.name, style = MaterialTheme.typography.bodyLarge)

        // Only the brand ellipsises: some OFF brands are a sentence and would squeeze the source
        // label to one character per line. `fill = false` keeps short brands beside the label.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!food.brand.isNullOrBlank()) {
                Text(
                    text = food.brand,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // The dataset each result comes from.
            food.provenance?.let(DataSourceAttribution::shortLabelFor)?.let { label ->
                if (!food.brand.isNullOrBlank()) {
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Text(
            text = EnergyFormat.format(food.per100g.calories) + " · " +
                macroPart("P", food.per100g, Nutrient.PROTEIN, food.per100g.proteinG, "g") +
                macroPart("F", food.per100g, Nutrient.FAT, food.per100g.fatG, "g") +
                macroPart("C", food.per100g, Nutrient.CARBS, food.per100g.carbsG, "g") +
                (if (food.nutritionBasis == com.lethio.macros.domain.model.NutritionBasis.MASS)
                    stringResource(R.string.per_100g) else "/ 100 ${stringResource(R.string.millilitres_short)}") +
                carbLabelSuffix(food.carbLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Spoken as words ("7 grams protein") when all three are known.
            modifier = spokenMacros(food)?.let { cd -> Modifier.semantics { contentDescription = cd } } ?: Modifier,
        )
    }
}

@Composable
private fun spokenMacros(food: Food): String? {
    val p = food.per100g.proteinG ?: return null
    val f = food.per100g.fatG ?: return null
    val c = food.per100g.carbsG ?: return null
    return EnergyFormat.format(food.per100g.calories) + ", " +
        stringResource(R.string.macro_breakdown_cd, p.roundToInt(), f.roundToInt(), c.roundToInt())
}


/** A titled block of previously logged foods. Quick-add entries are skipped: there is nothing to reopen. */
private fun LazyListScope.shortcutSection(
    title: String,
    entries: List<LogEntry>,
    onSelected: (Long, String) -> Unit,
) {
    val actionable = entries.filter { it.foodRef.id != null }
    if (actionable.isEmpty()) return

    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() },
        )
    }
    items(actionable, key = { "$title-${it.id}" }) { entry ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    entry.foodRef.id?.let { id -> onSelected(id, entry.foodRef.kind.value) }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(entry.foodName, style = MaterialTheme.typography.bodyLarge)
            // Favourites have no quantity; don't show "0 g".
            if (entry.quantity.basisAmount > 0) {
                Text(
                    text = "${entry.quantity.amount.formatQuantity()} " +
                        "${PortionLabels.of(entry.quantity.unit)} · " +
                        EnergyFormat.format(entry.macros.calories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!entry.brand.isNullOrBlank()) {
                Text(
                    text = entry.brand,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}
