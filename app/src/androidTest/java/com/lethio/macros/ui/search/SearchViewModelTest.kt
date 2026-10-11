package com.lethio.macros.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.SettingsManager
import com.lethio.macros.domain.model.*
import com.lethio.macros.domain.repository.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real view model; controlled repositories reveal extra searches and subscriptions. */
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun food(id: Long) = Food(FoodRef.Bundled(id), "Milk $id", per100g = Macros(50.0))

    private fun entry(food: Food) =
        LogEntry(
            id = food.ref.id!!,
            date = LocalDate.of(2026, 9, 29),
            meal = MealType.BREAKFAST,
            foodRef = food.ref,
            foodName = food.name,
            quantity = Quantity.ofGrams(100.0),
            macros = food.per100g,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private class Foods(val rows: List<Food>) : FoodRepository {
        var searches = 0

        override suspend fun search(query: String, limit: Int): List<Food> {
            searches++
            return rows
        }

        override suspend fun findByBarcode(barcode: String): Food? = null

        override suspend fun getByRef(ref: FoodRef): Food? = rows.firstOrNull { it.ref == ref }

        override suspend fun saveCustomFood(food: Food): FoodRef = error("No save expected")

        override suspend fun deleteCustomFood(ref: FoodRef.Custom) = error("No delete expected")

        override suspend fun activeDatabase(): ActiveDatabase? = null
    }

    private class Logs : LogRepository {
        val favorites = MutableStateFlow<List<LogEntry>>(emptyList())
        var subscriptions = 0
        var recents: List<LogEntry> = emptyList()

        override fun observeFavorites(): Flow<List<LogEntry>> =
            favorites.onStart { subscriptions++ }

        override suspend fun recentFoods(limit: Int): List<LogEntry> = recents

        override fun observeDay(date: LocalDate): Flow<DayLog> = error("No day expected")

        override fun observeLoggedDates(): Flow<List<LocalDate>> = error("No dates expected")

        override suspend fun getEntry(id: Long): LogEntry? = null

        override suspend fun addEntry(entry: LogEntry): Long = error("No insert expected")

        override suspend fun updateEntry(entry: LogEntry) = error("No update expected")

        override suspend fun clearDay(
            date: java.time.LocalDate
        ): List<com.lethio.macros.domain.model.LogEntry> = error("not used")

        override suspend fun restoreEntries(
            entries: List<com.lethio.macros.domain.model.LogEntry>
        ): Unit = error("not used")

        override suspend fun deleteEntry(id: Long) = error("No delete expected")

        override suspend fun restoreEntry(entry: LogEntry) = error("No restore expected")

        override suspend fun allEntries(): List<LogEntry> = emptyList()

        override fun observeIsFavorite(ref: FoodRef): Flow<Boolean> = error("No item expected")

        override suspend fun toggleFavorite(entry: LogEntry) = error("No toggle expected")
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        repeat(150) {
            if (condition()) return
            delay(20)
        }
        error("Timed out waiting for view model state")
    }

    @Test
    fun favoritesAndRecentsRerankWithoutAnotherSearch() =
        runBlocking(Dispatchers.Main) {
            val ordinary = food(1)
            val recent = food(2)
            val favorite = food(3)
            val both = food(4)
            val foods = Foods(listOf(ordinary, recent, favorite, both))
            val logs =
                Logs().apply {
                    recents = listOf(entry(recent), entry(both))
                    favorites.value = listOf(entry(favorite))
                }
            val model = SearchViewModel(foods, logs, SettingsManager(context))
            awaitCondition {
                logs.subscriptions == 1 &&
                    model.state.value.favorites.size == 1 &&
                    model.state.value.recents.size == 2
            }
            model.onQueryChanged("milk")
            awaitCondition { model.state.value.hasSearched }
            assertThat(model.state.value.results)
                .containsExactly(favorite, recent, both, ordinary)
                .inOrder()

            logs.favorites.value = listOf(entry(both), entry(favorite))
            awaitCondition { model.state.value.favorites.size == 2 }
            assertThat(model.state.value.results)
                .containsExactly(favorite, both, recent, ordinary)
                .inOrder()
            logs.favorites.value = emptyList()
            awaitCondition { model.state.value.favorites.isEmpty() }
            assertThat(model.state.value.results)
                .containsExactly(recent, both, ordinary, favorite)
                .inOrder()
            logs.recents = listOf(entry(ordinary))
            model.refreshRecents()
            awaitCondition { model.state.value.recents.singleOrNull()?.foodRef == ordinary.ref }
            assertThat(model.state.value.results)
                .containsExactly(ordinary, recent, favorite, both)
                .inOrder()
            assertThat(foods.searches).isEqualTo(1)
            assertThat(logs.subscriptions).isEqualTo(1)
        }

    @Test
    fun hiddenBarcodeFavoriteStaysInShortcutsButNotTypedResults() =
        runBlocking(Dispatchers.Main) {
            val winner = food(10)
            val hiddenVariant = food(11)
            val foods = Foods(listOf(winner)) // Search returned only the FTS display winner.
            val logs = Logs().apply { favorites.value = listOf(entry(hiddenVariant)) }
            val model = SearchViewModel(foods, logs, SettingsManager(context))
            awaitCondition { model.state.value.favorites.size == 1 }
            model.onQueryChanged("milk")
            awaitCondition { model.state.value.hasSearched }
            assertThat(model.state.value.favorites.single().foodRef).isEqualTo(hiddenVariant.ref)
            assertThat(model.state.value.results).containsExactly(winner)
            logs.favorites.value = listOf(entry(winner), entry(hiddenVariant))
            awaitCondition { model.state.value.favorites.size == 2 }
            assertThat(model.state.value.results).containsExactly(winner)
            assertThat(foods.searches).isEqualTo(1)
        }
}
