package com.lethio.macros.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.food.boostKnownFoods as promoteKnownFoods
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.domain.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Food> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    /** Shown when the query is empty — the fast path for foods already eaten. */
    val recents: List<LogEntry> = emptyList(),
    val favorites: List<LogEntry> = emptyList(),
    /**
     * The database the search was restricted to and its languages, for the empty state; null when
     * the search covered every generic source.
     */
    val searchedDatabase: SearchedDatabase? = null,
) {
    val showingShortcuts: Boolean get() = query.isBlank()
}

/** See [SearchUiState.searchedDatabase]. */
data class SearchedDatabase(val source: String, val languages: List<String>)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val logRepository: LogRepository,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var recentRefs: Set<FoodRef> = emptySet()

    /** Keep the DAO order so changing personal history never changes catalogue ranking. */
    private var unboostedResults: List<Food> = emptyList()
    private var favoriteRefs: Set<FoodRef> = emptySet()

    init {
        viewModelScope.launch {
            logRepository.observeFavorites().collect { favorites ->
                favoriteRefs = favorites.map { it.foodRef }.toSet()
                _state.value = _state.value.copy(
                    favorites = favorites,
                    results = boostKnownFoods(unboostedResults),
                )
            }
        }
        refreshRecents()
    }

    /** Recents come from the log, so they must be re-read whenever this screen is shown. */
    fun refreshRecents() {
        viewModelScope.launch {
            val recents = logRepository.recentFoods(limit = 12)
            recentRefs = recents.map { it.foodRef }.toSet()
            _state.value = _state.value.copy(
                recents = recents,
                results = boostKnownFoods(unboostedResults),
            )
        }
    }

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()

        if (query.trim().length < MIN_QUERY_LENGTH) {
            unboostedResults = emptyList()
            _state.value = _state.value.copy(
                results = emptyList(),
                isSearching = false,
                hasSearched = false,
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.value = _state.value.copy(isSearching = true)
            unboostedResults = foodRepository.search(query)
            _state.value = _state.value.copy(
                results = boostKnownFoods(unboostedResults),
                isSearching = false,
                hasSearched = true,
            )
            // For the empty state and the borrowed banner; settings and a cached map, no query.
            _state.value = _state.value.copy(
                searchedDatabase = foodRepository.activeDatabase()?.let {
                    SearchedDatabase(it.source, it.languages)
                },
            )
        }
    }


    /** Lifts favourites to the top with a stable partition, so query order holds within each group. */
    private fun boostKnownFoods(results: List<Food>): List<Food> =
        promoteKnownFoods(results, favoriteRefs, recentRefs)

    private companion object {
        const val MIN_QUERY_LENGTH = 2

        const val DEBOUNCE_MS = 250L
    }
}
