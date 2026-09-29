package com.lethio.macros.data.food

import com.lethio.macros.SettingsManager
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toCustomFoodEntity
import com.lethio.macros.data.toCustomServingEntity
import com.lethio.macros.data.toDomain
import com.lethio.macros.data.user.CustomFoodDao
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.repository.ActiveDatabase
import com.lethio.macros.domain.repository.FoodRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FoodRepositoryImpl @Inject constructor(
    private val foodDatabase: FoodDatabaseProvider,
    private val compoundLexicon: CompoundLexicon,
    private val tableLanguages: TableLanguages,
    private val customFoodDao: CustomFoodDao,
    private val timeProvider: TimeProvider,
    private val settingsManager: SettingsManager,
) : FoodRepository {

    private suspend fun currentLanguage(): String =
        SettingsManager.languageFor(settingsManager.languagePreference.first())

    private suspend fun foodLanguages(nationalSource: String): List<String> =
        SettingsManager.derivedFoodLanguages(
            settingsManager.foodLanguagePreference.first(),
            currentLanguage(),
            tableLanguages.forSource(nationalSource),
        )

    private suspend fun currentNationalSource(): String = SettingsManager.nationalSourceFor(
        SettingsManager.countryFor(settingsManager.countryPreference.first()),
    )

    private suspend fun foodLanguages(): List<String> = foodLanguages(currentNationalSource())

    override suspend fun search(query: String, limit: Int): List<Food> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val custom = customFoodDao
            .search(escapeForLike(foldForSearch(trimmed)), limit)
            .map { it.toDomain() }

        val sanitized = FtsQuery.sanitize(trimmed)
        val bundled = if (sanitized == null) {
            emptyList()
        } else {
            val remaining = (limit - custom.size).coerceAtLeast(0)
            if (remaining == 0) {
                emptyList()
            } else {

                val country = SettingsManager.countryFor(settingsManager.countryPreference.first())
                val nationalSource = SettingsManager.nationalSourceFor(country)

                val languages = foodLanguages(nationalSource)
                val dao = foodDatabase.dao()
                val languagePriority = SettingsManager.foodLanguagePriority(languages)

                var ftsQuery = sanitized

                var plainQuery = stripLikeWildcards(foldForSearch(trimmed))

                var primary = dao.searchPrimarySource(
                    ftsQuery, plainQuery, languages, languagePriority, nationalSource,
                    "", remaining,
                )

                var rescueHead = ""
                if (primary.none { it.matchTier <= HEAD_MATCH_TIER }) {
                    val rescue = FtsQuery.rescueVariant(
                        plainQuery, nationalSource, compoundLexicon.lexicons(),
                    )
                    val rescueFts = rescue?.let { FtsQuery.sanitize(it.query) }
                    if (rescue != null && rescueFts != null) {
                        rescueHead = rescue.head
                        ftsQuery = rescueFts
                        plainQuery = rescue.query
                        primary = dao.searchPrimarySource(
                            ftsQuery, plainQuery, languages, languagePriority, nationalSource,
                            rescueHead, remaining,
                        )
                    }
                }

                val fallback = if (nationalSource.isNotEmpty() && primary.isEmpty()) {

                    val lenders = languages.firstOrNull()
                        ?.let { tableLanguages.sourcesServing(listOf(it), nationalSource) }
                        .orEmpty()
                    if (lenders.isEmpty()) {
                        emptyList()
                    } else {
                        dao.searchBorrowedGeneric(
                            ftsQuery, plainQuery, languages, languagePriority,
                            lenders.joinToString(",", prefix = ",", postfix = ","),
                            rescueHead, remaining,
                        )
                    }
                } else {
                    emptyList()
                }
                val genericCount = (primary + fallback).distinctBy { it.result.food.id }.size
                val branded = if (genericCount < remaining) {
                    dao.searchBranded(
                        ftsQuery, plainQuery, remaining - genericCount,
                    )
                } else {
                    emptyList()
                }
                composeSearchResults(
                    primary = primary,
                    fallback = fallback,

                    branded = branded.map { RankedFood(it, matchTier = Int.MAX_VALUE) },
                    limit = remaining,
                    keyOf = { it.result.food.id },
                    tierOf = { it.matchTier },
                ).map { it.result.toDomain() }
            }
        }

        return (custom + bundled).take(limit)
    }

    override suspend fun findByBarcode(barcode: String): Food? {
        customFoodDao.findByBarcode(barcode)?.let { food ->
            return food.toDomain(customFoodDao.servingsFor(food.id))
        }
        val languages = foodLanguages()
        return foodDatabase.dao().findByBarcode(
            barcode,
            languages,
            SettingsManager.foodLanguagePriority(languages),
        )?.let { food ->
            food.toDomain(foodDatabase.dao().servingsFor(food.food.id))
        }
    }

    override suspend fun getByRef(ref: FoodRef): Food? = when (ref) {
        is FoodRef.Custom -> customFoodDao.getById(ref.id)
            ?.let { it.toDomain(customFoodDao.servingsFor(it.id)) }

        is FoodRef.Bundled -> foodLanguages().let { languages ->
            foodDatabase.dao()
                .getById(ref.id, languages, SettingsManager.foodLanguagePriority(languages))
                ?.let { it.toDomain(foodDatabase.dao().servingsFor(it.food.id)) }
        }

        FoodRef.QuickAdd -> null
    }

    override suspend fun saveCustomFood(food: Food): FoodRef {
        val now = timeProvider.now()

        val createdAt = (food.ref as? FoodRef.Custom)
            ?.let { customFoodDao.getById(it.id)?.createdAt }
            ?.let(Instant::ofEpochMilli)
            ?: now
        val id = customFoodDao.upsertWithServings(
            food = food.toCustomFoodEntity(now, createdAt = createdAt),
            servings = food.servings.mapIndexed { index, serving ->
                serving.toCustomServingEntity(foodId = 0, sortOrder = index)
            },
        )
        return FoodRef.Custom(id)
    }

    override suspend fun availableNameLanguages(): List<String> =
        foodDatabase.dao().availableNameLanguages()

    override suspend fun activeDatabase(): ActiveDatabase? {
        val source = currentNationalSource()
        if (source.isEmpty()) return null
        return ActiveDatabase(source, foodLanguages(source))
    }

    override suspend fun deleteCustomFood(ref: FoodRef.Custom) {
        customFoodDao.getById(ref.id)?.let { customFoodDao.delete(it) }
    }
}
