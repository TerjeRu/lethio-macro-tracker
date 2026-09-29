package com.lethio.macros.ui.search

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

@Composable
private fun emptyInDatabase(db: SearchedDatabase): String {
    val label = DataSource.fromValue(db.source)
        ?.let(DataSourceAttribution::shortLabelFor)
        ?: db.source
    val first = stringResource(R.string.no_results_in_database, label)
    val appLanguage = Locale.getDefault().language
    val other = db.languages.firstOrNull { it != appLanguage } ?: return first

    val name = Locale.forLanguageTag(other).getDisplayLanguage().ifEmpty { other }
    return first + " " + stringResource(R.string.no_results_in_database_language, label, name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onFoodSelected: (foodId: Long, foodKind: String) -> Unit,
    onAddManually: (query: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) listState.scrollToItem(0)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshRecents() }

    Scaffold(
        topBar = {

            TopAppBar(title = { Text(stringResource(R.string.search_foods)) })
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

            val favoritesTitle = stringResource(R.string.favorites)
            val recentTitle = stringResource(R.string.recent)
            val emptyHint = stringResource(R.string.search_to_get_started)
            val noResults = stringResource(R.string.no_results)

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

                            EmptyState(
                                message = state.searchedDatabase?.let { db ->
                                    emptyInDatabase(db)
                                } ?: noResults,
                                icon = Icons.Default.SearchOff,
                            )

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
                "P${food.per100g.proteinG.roundToInt()}g " +
                "F${food.per100g.fatG.roundToInt()}g " +
                "C${food.per100g.carbsG.roundToInt()}g " +
                stringResource(R.string.per_100g) +
                carbLabelSuffix(food.carbLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

            if (entry.quantity.grams > 0) {
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
