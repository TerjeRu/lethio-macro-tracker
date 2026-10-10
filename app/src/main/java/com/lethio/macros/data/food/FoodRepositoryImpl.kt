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

    // From the setting, not Locale.getDefault(): the in-app picker overrides the device.
    private suspend fun currentLanguage(): String =
        SettingsManager.languageFor(settingsManager.languagePreference.first())

    /**
     * The food-name languages, derived from the chosen table unless the reader stored a choice.
     * See [SettingsManager.derivedFoodLanguages].
     */
    private suspend fun foodLanguages(nationalSource: String): List<String> =
        SettingsManager.derivedFoodLanguages(
            settingsManager.foodLanguagePreference.first(),
            currentLanguage(),
            tableLanguages.forSource(nationalSource),
        )

    /** The national table the reader's country selects, or `""` for an unmapped country. */
    private suspend fun currentNationalSource(): String = SettingsManager.nationalSourceFor(
        SettingsManager.countryFor(settingsManager.countryPreference.first()),
    )

    /**
     * For barcode and by-id lookups, which must resolve the same languages a search would, or a
     * food would change name between being found and being opened.
     */
    private suspend fun foodLanguages(): List<String> = foodLanguages(currentNationalSource())

    /**
     * The user's own foods come first and do not count against [limit]: a food someone created
     * should never be pushed off the list.
     */
    override suspend fun search(query: String, limit: Int): List<Food> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Custom foods are few, so LIKE is enough; folded to match `name_folded`, and escaped
        // because nothing else filters the query here.
        val custom = customFoodDao
            .search(escapeForLike(foldForSearch(trimmed)), limit)
            .map { it.toDomain() }

        // Null: nothing searchable once FTS5 syntax is stripped.
        val sanitized = FtsQuery.sanitize(trimmed)
        val bundled = if (sanitized == null) {
            emptyList()
        } else {
            val remaining = (limit - custom.size).coerceAtLeast(0)
            if (remaining == 0) {
                emptyList()
            } else {
                // The country decides the table, and the table the food-name languages.
                val country = SettingsManager.countryFor(settingsManager.countryPreference.first())
                val nationalSource = SettingsManager.nationalSourceFor(country)
                // See FoodDao's LEAD_WORD_MATCH. Primary shelf only.
                val leadWordSource =
                    if (nationalSource in SettingsManager.LEAD_WORD_SOURCES) nationalSource else ""
                val languages = foodLanguages(nationalSource)
                val dao = foodDatabase.dao()
                val languagePriority = SettingsManager.foodLanguagePriority(languages)
                // Not val: the rescue below may replace both, and every later shelf must search
                // for the same thing.
                var ftsQuery = sanitized
                // Stripped, not escaped; see `stripLikeWildcards`.
                var plainQuery = stripLikeWildcards(foldForSearch(trimmed))
                // Generic foods first: the national table, or every generic source when there is
                // none. Branded products never displace generic foods in the merge.
                var primary = dao.searchPrimarySource(
                    ftsQuery, plainQuery, languages, languagePriority, nationalSource,
                    "", leadWordSource, remaining,
                )
                // A compound stored as separate words (`svinekjøtt` against `Svin, nakkekoteletter`)
                // or a plural of a singular name (`linssit` against `Linssi`) cannot be reached by
                // prefix matching. Fires when the reader's own table has no head match, not only on
                // zero rows: `storfekjøtt` does match beef fat. Costs nothing when it does not fire.
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
                            rescueHead, leadWordSource, remaining,
                        )
                    }
                }
                // Borrowing from other tables, when the reader's own has no row at all.
                val fallback = if (nationalSource.isNotEmpty() && primary.isEmpty()) {
                    // Some everyday foods are missing from a table (Switzerland has no cucumber).
                    // Zero rows is the trigger: a table that has the food under a name the query
                    // cannot prefix-match should not be overruled by another country's data.
                    // Lenders must serve the reader's primary language; nearly every table has
                    // English aliases, so "any shared language" would lend Norwegian bread to a Dane.
                    val lenders = languages.firstOrNull()
                        ?.let { tableLanguages.sourcesServing(listOf(it), nationalSource) }
                        .orEmpty()
                    if (lenders.isEmpty()) {
                        emptyList()
                    } else {
                        dao.searchBorrowedGeneric(
                            ftsQuery, plainQuery, languages, languagePriority,
                            lenders.joinToString(",", prefix = ",", postfix = ","),
                            rescueHead, "", remaining,
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
                    // Branded rows have no tier and are appended last; the sentinel sorts after any tier.
                    branded = branded.map { RankedFood(it, matchTier = Int.MAX_VALUE) },
                    limit = remaining,
                    keyOf = { it.result.food.id },
                    tierOf = { it.matchTier },
                ).map { it.result.toDomain() }
            }
        }

        return (custom + bundled).take(limit)
    }

    /** Custom foods win, so a user's correction of a bundled record takes effect. */
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

        // A quick-add has no food record.
        FoodRef.QuickAdd -> null
    }

    override suspend fun saveCustomFood(food: Food): FoodRef {
        val now = timeProvider.now()
        // Keep the original creation time: `insert` replaces the row, so it must be carried over.
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

    /** Null for an unmapped country, whose search covers every generic source. */
    override suspend fun activeDatabase(): ActiveDatabase? {
        val source = currentNationalSource()
        if (source.isEmpty()) return null
        return ActiveDatabase(source, foodLanguages(source))
    }

    override suspend fun deleteCustomFood(ref: FoodRef.Custom) {
        customFoodDao.getById(ref.id)?.let { customFoodDao.delete(it) }
    }
}
