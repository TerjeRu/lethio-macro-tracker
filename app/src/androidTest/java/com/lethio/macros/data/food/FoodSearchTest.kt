package com.lethio.macros.data.food

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.food.entity.FoodEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real shipped `seed.db` asset through the real DAO. */
@RunWith(AndroidJUnit4::class)
class FoodSearchTest {

    private lateinit var provider: FoodDatabaseProvider

    /** The compound lexicon asset, read the same way the app reads it. */
    private lateinit var lexicon: CompoundLexicon

    /** Borrowing needs the same source-language map [FoodRepositoryImpl] asks. */
    private lateinit var tableLanguages: TableLanguages

    private suspend fun dao(): FoodDao = provider.dao()

    /**
     * Runs a search the way [FoodRepositoryImpl] does, through the same sanitiser and folding.
     *
     * Returns the wrapped [FoodEntity], since these assertions are about the row's own fields.
     * [nationalSource] defaults to "" (no preference), the app's default state.
     */
    private suspend fun search(
        term: String,
        limit: Int,
        language: String = "en",
        nationalSource: String = "",
    ): List<FoodEntity> =
        searchCurrent(
                term = term,
                limit = limit,
                languages = langs(language),
                nationalSource = nationalSource,
            )
            .map { it.food }

    /**
     * The display/ranking language is a priority list. A reader with one language resolves to
     * `[language, en]`, so single-language tests go through here.
     */
    private fun langs(vararg languages: String): List<String> =
        SettingsManager.foodLanguagesFor(languages.joinToString(","), "en")

    private val PRIORITY_EN: String
        get() = SettingsManager.foodLanguagePriority(langs("en"))

    private val GENERIC_SOURCES =
        listOf(
            "usda",
            "ciqual",
            "bls",
            "bedca",
            "cofid",
            "matvaretabellen",
            "livsmedel",
            "fineli",
            "frida",
            "swiss",
            "tca",
            "cnf",
            "afcd",
            "nevo",
            "foodfiles",
        )

    private suspend fun searchGeneric(
        term: String,
        limit: Int,
        nationalSource: String,
    ): List<FoodWithDisplayName> =
        dao()
            .searchPrimarySource(
                FtsQuery.sanitize(term)!!,
                foldForSearch(term),
                langs("en"),
                PRIORITY_EN,
                nationalSource,
                "",
                "",
                limit,
            )
            .map { it.result }

    private suspend fun searchCurrent(
        term: String,
        limit: Int,
        languages: List<String>,
        nationalSource: String = "",
    ): List<FoodWithDisplayName> {
        val priority = SettingsManager.foodLanguagePriority(languages)
        var fts = FtsQuery.sanitize(term)!!
        var plain = foldForSearch(term)
        // Mirrors FoodRepositoryImpl: the lead-word rule applies on these primary shelves only.
        val leadWordSource =
            if (nationalSource in SettingsManager.LEAD_WORD_SOURCES) nationalSource else ""
        var primary =
            dao()
                .searchPrimarySource(
                    fts,
                    plain,
                    languages,
                    priority,
                    nationalSource,
                    "",
                    leadWordSource,
                    limit,
                )
        // The rescue, mirrored from FoodRepositoryImpl: when the reader's own shelf has no
        // head match, retry with a shorter form of what they typed. Mirrored rather than left out
        // because these assertions are about the order the app produces -- a helper that skipped
        // the rescue would be testing a search the app no longer runs.
        var head = ""
        if (primary.none { it.matchTier <= HEAD_MATCH_TIER }) {
            val rescue = FtsQuery.rescueVariant(plain, nationalSource, lexicon.lexicons())
            val rescueFts = rescue?.let { FtsQuery.sanitize(it.query) }
            if (rescue != null && rescueFts != null) {
                head = rescue.head
                fts = rescueFts
                plain = rescue.query
                primary =
                    dao()
                        .searchPrimarySource(
                            fts,
                            plain,
                            languages,
                            priority,
                            nationalSource,
                            head,
                            leadWordSource,
                            limit,
                        )
            }
        }
        // Mirrors FoodRepositoryImpl's borrow: a zero-row miss on the reader's own table borrows
        // from sources serving their primary language.
        val fallback =
            if (nationalSource.isNotEmpty() && primary.isEmpty()) {
                val lenders =
                    languages
                        .firstOrNull()
                        ?.let { tableLanguages.sourcesServing(listOf(it), nationalSource) }
                        .orEmpty()
                if (lenders.isEmpty()) {
                    emptyList()
                } else {
                    dao()
                        .searchBorrowedGeneric(
                            fts,
                            plain,
                            languages,
                            priority,
                            lenders.joinToString(",", prefix = ",", postfix = ","),
                            head,
                            "",
                            limit,
                        )
                }
            } else {
                emptyList()
            }
        val branded = dao().searchBranded(fts, plain, limit)
        // Mirrors FoodRepositoryImpl exactly, tier included -- these assertions are about the
        // order the app produces, so a helper that merged differently would test nothing.
        return composeSearchResults(
                primary = primary,
                fallback = fallback,
                branded = branded.map { RankedFood(it, matchTier = Int.MAX_VALUE) },
                limit = limit,
                keyOf = { it.result.food.id },
                tierOf = { it.matchTier },
            )
            .map { it.result }
    }

    private suspend fun searchBranded(term: String, limit: Int): List<FoodEntity> =
        dao().searchBranded(FtsQuery.sanitize(term)!!, foldForSearch(term), limit).map { it.food }

    /** Every generic source except [nationalSource], ranked by the shared generic ladder. */
    private suspend fun searchFallback(
        term: String,
        limit: Int,
        nationalSource: String,
        language: String = "en",
    ): List<FoodWithDisplayName> =
        dao()
            .searchBorrowedGeneric(
                FtsQuery.sanitize(term)!!,
                foldForSearch(term),
                langs(language),
                SettingsManager.foodLanguagePriority(langs(language)),
                GENERIC_SOURCES.filter { it != nationalSource }
                    .joinToString(",", prefix = ",", postfix = ","),
                "",
                "",
                limit,
            )
            .map { it.result }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Delete first so every run exercises the real first-launch path: seed extracted from
        // assets, then opened. Going through FoodDatabaseProvider rather than building Room
        // directly is deliberate — the first version of this test configured its own database and
        // therefore tested something the app never actually runs.
        context.deleteDatabase(FoodDatabase.NAME)
        provider = FoodDatabaseProvider(context)
        lexicon = CompoundLexicon(context)
        tableLanguages = TableLanguages(provider)
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(FoodDatabase.NAME)
    }

    @Test
    fun seedAssetIsActuallyLoaded() {
        runBlocking {
            val count = dao().count()
            // Room opening the asset at all proves the identity hash matches; a mismatch throws.
            assertThat(count).isGreaterThan(50_000)
        }
    }

    @Test
    fun searchReturnsResultsForEverydayTerms() = runBlocking {
        // These exact three were the original acceptance checklist for the database rebuild,
        // chosen because all three returned nothing before that work: search had never functioned
        // in this project's history.
        listOf("melk", "leche", "chicken").forEach { term ->
            val query = FtsQuery.sanitize(term)
            assertThat(query).isNotNull()
            val results = search(term, limit = 20)
            assertThat(results).isNotEmpty()
        }
    }

    @Test
    fun theLettersAligatureStandsForReachTheLigatureSpelling() = runBlocking {
        // 322 names in the seed carry œ, æ or ß, which `remove_diacritics 2` does not split.
        // `food_search` indexes `name_folded`, where each ligature is already two letters. Both
        // terms leave headroom: "oeufs frais" returns 16 ligature-spelled names in its top 50.
        mapOf("oeufs frais" to '\u0153', "eiweissbrot" to '\u00df').forEach { (asTyped, ligature) ->
            val results = search(asTyped, limit = 50)
            assertThat(results).isNotEmpty()
            assertThat(results.any { it.name.contains(ligature) }).isTrue()
        }
    }

    @Test
    fun theLigatureSpellingItselfKeepsWorking() = runBlocking {
        // The other direction, which is the one a folded index could silently break: someone on a
        // French or German keyboard who types the ligature must still find the food. Folding the
        // index without folding the query in `FtsQuery.sanitize` would take this away, and only
        // this assertion would notice.
        listOf("Œufs frais", "Eiweißbrot").forEach { term ->
            assertThat(search(term, limit = 50)).isNotEmpty()
        }
    }

    @Test
    fun aGermanAliasIsReachableByTheWordAsItIsSpelledAndAsItIsTyped() = runBlocking {
        // All three spellings of *Süßmolke*, including the one a keyboard without ß produces,
        // must reach the same German row.
        listOf("Süßmolke", "Sussmolke", "sussmolke").forEach { term ->
            val results = searchCurrent(term, limit = 50, languages = langs("de"))
            assertThat(results.map { it.displayName }).contains("Süßmolke")
        }
    }

    @Test
    fun searchIsPrefixMatchingSoResultsAppearWhileTyping() = runBlocking {
        val results = search("chick", limit = 20)
        assertThat(results).isNotEmpty()
        assertThat(results.any { it.name.contains("chick", ignoreCase = true) }).isTrue()
    }

    @Test
    fun syntacticallyHostileInputDoesNotThrow() = runBlocking {
        // Every one of these is reachable by typing normally into a search box. Before the
        // sanitiser each would raise SQLiteException from inside a Flow and crash the screen.
        listOf("it's", "a*b", "\"", "milk OR bread", "NOT milk", "a:b", "(", "-", "***").forEach {
            input ->
            val query = FtsQuery.sanitize(input)
            if (query != null) {
                search(input, limit = 10) // must not throw
            }
        }
    }

    @Test
    fun genericFoodsAreRankedAheadOfObscureBrandedOnes() = runBlocking {
        val results = search("banana", limit = 10)
        assertThat(results).isNotEmpty()
        // The seed always contains the full generic set, so a plain search for a whole food
        // should surface lab-measured data rather than only branded novelty products.
        assertThat(results.any { it.source in setOf("usda", "ciqual") }).isTrue()
    }

    @Test
    fun everydayWordsReturnTheActualFood() = runBlocking {
        // Ranking by bm25 alone gives banana -> Bananasplit, egg -> Eggnog, bread -> Breadcrumbs.
        // The property is "the top hit is the food itself", asserted via basic_rank and on the
        // DISPLAY name: "banana" can win with Matvaretabellen's "Banan, rå", shown in English.
        listOf(
                "banana",
                "milk",
                "egg",
                "bread",
                "apple",
                "potato",
                "tomato",
                "carrot",
                "salmon",
                "butter",
                "orange",
                "rice",
            )
            .forEach { term ->
                val top = searchCurrent(term, limit = 5, languages = langs("en")).first()
                assertThat(top.displayName.lowercase()).contains(term)
                assertThat(top.food.basicRank).isLessThan(2) // 2 = a derivative: flour, powder, oil
            }
    }

    @Test
    fun aPlainFoodNameDoesNotReturnACompoundFood() = runBlocking {
        // "Butter, salted" is butter; "Butter bean" is a different food that merely starts with
        // the same word. USDA and Ciqual both write "Food, part, preparation", so a comma after
        // the query means qualifiers follow and a space means a compound noun.
        mapOf("butter" to "butter bean", "orange" to "orange peel").forEach { (term, mustNotBe) ->
            val top = search(term, limit = 5).first()
            assertThat(top.name.lowercase()).doesNotContain(mustNotBe)
        }
    }

    @Test
    fun multiWordQualifierQueriesRecognizeCommaSeparatedNames() = runBlocking {
        // National tables commonly write a head and qualifier as "head, qualifier". Replacing
        // the comma with a space must not create a double space that makes the tier ladder miss the
        // leading match and lets an unrelated row win on fallback terms.
        mapOf("butter salted" to "butter", "milk whole" to "milk", "bread wholemeal" to "bread")
            .forEach { (term, expectedHead) ->
                // Display name, for the same reason as everydayWordsReturnTheActualFood: "milk
                // whole"
                // now reaches Frida's "Sødmælk (UHT)", shown to an English reader as whole milk.
                val top = searchCurrent(term, limit = 5, languages = langs("en")).first()
                assertThat(top.displayName.lowercase()).contains(expectedHead)
                assertThat(top.food.basicRank).isLessThan(2)
            }
    }

    @Test
    fun primarySourceSearchKeepsGenericRowsLocalAndRetainsOffProducts() = runBlocking {
        val results = searchGeneric("milk", limit = 20, nationalSource = "usda").map { it.food }
        assertThat(results).isNotEmpty()
        assertThat(results.all { it.source == "usda" }).isTrue()
        assertThat(searchBranded("milk", limit = 5).all { it.source == "off" }).isTrue()
    }

    @Test
    fun everyMappedCountryResolvesToASourceWithRows() = runBlocking {
        // A country whose national source is mapped but empty leaves the reader with a primary
        // shelf of nothing. The term has to be in each table's OWN language. "milk" finds nothing
        // at all in `tca`, which stores Portuguese names and carries no English alias for any row
        // -- a real gap, a data gap, not a reason to weaken this test.
        val milk =
            mapOf(
                "US" to "milk",
                "UK" to "milk",
                "CA" to "milk",
                "AU" to "milk",
                "FR" to "lait",
                "DE" to "milch",
                "ES" to "leche",
                "NO" to "melk",
                "SE" to "mjolk",
                "FI" to "maito",
                "DK" to "maelk",
                "CH" to "milch",
                "PT" to "leite",
            )
        val empty = mutableListOf<String>()
        for ((country, term) in milk) {
            val source = SettingsManager.nationalSourceFor(country)
            assertThat(source).isNotEmpty()
            if (searchGeneric(term, limit = 5, nationalSource = source).isEmpty()) {
                empty += "$country/$source"
            }
        }
        assertThat(empty).isEmpty()
    }

    @Test
    fun anUnmappedCountryStillSearchesEveryGenericSource() = runBlocking {
        // The default for anyone outside the thirteen. "" must mean "every generic source", not
        // "none" -- the latter left a default-configuration reader with branded rows only, which
        // was the release blocker the solyluna branch was cut to fix.
        assertThat(SettingsManager.nationalSourceFor("JP")).isEmpty()
        val results = search("milk", limit = 20, nationalSource = "")
        assertThat(results).isNotEmpty()
        assertThat(results.none { it.source == "off" }).isTrue()
        assertThat(results.map { it.source }.distinct().size).isAtLeast(3)
    }

    @Test
    fun restrictingToOneSourceReturnsOnlyThatSourceAndStillFindsEverydayFoods() = runBlocking {
        // The "only this source" path. Off, a Norwegian reader gets their own table and
        // then everyone else's; on, only their own. The invariant is that "on" narrows to exactly
        // one source and does not empty the page for ordinary foods.
        val terms = listOf("melk", "brod", "kylling", "ris", "eple")
        for (term in terms) {
            val restricted = searchGeneric(term, limit = 20, nationalSource = "matvaretabellen")
            assertThat(restricted).isNotEmpty()
            assertThat(restricted.all { it.food.source == "matvaretabellen" }).isTrue()
        }
    }

    @Test
    fun restrictingToOneSourceIsMeasurablyNarrowerThanTheDefault() = runBlocking {
        // "tofu" because matvaretabellen has 4 rows for it, so the fallback visibly contributes.
        // "melk" fills the page from Norway alone and would make the two paths look identical.
        val restricted = searchGeneric("tofu", limit = 30, nationalSource = "matvaretabellen")
        val everyone = searchCurrent("tofu", 30, langs("nb"), "matvaretabellen")
        assertThat(restricted.size).isAtMost(everyone.size)
        assertThat(everyone.map { it.food.source }.distinct().size).isAtLeast(2)
        assertThat(restricted.map { it.food.source }.distinct()).containsExactly("matvaretabellen")
        Unit
    }

    @Test
    fun finlandServesAFinnAndAFinlandSwedeFromTheSameTable() = runBlocking {
        // Fineli carries
        // Swedish names as well as Finnish, so both readers must reach it -- and each must be
        // shown the name in the language they asked for first.
        val finn = searchGeneric("maito", limit = 5, nationalSource = "fineli")
        assertThat(finn).isNotEmpty()

        // Fineli carries Swedish names on 4,179 rows, so a Finland-Swede reaches the same table
        // by typing Swedish. Asserted on the primary shelf, not on a merged search: the merge
        // lets other sources onto the page when Fineli has no head
        // match, so "every row is fineli" would be asserting the opposite of the merge.
        val finlandSwede = searchGeneric("mjolk", limit = 5, nationalSource = "fineli")
        assertThat(finlandSwede).isNotEmpty()

        // And each reader is shown the name in the language they asked for first.
        val shownToFinn = searchCurrent("maito", 5, langs("fi"), "fineli").first().displayName
        val shownToSwede =
            searchCurrent("mjolk", 5, langs("sv", "fi"), "fineli").first().displayName
        assertThat(shownToFinn).isNotEmpty()
        assertThat(shownToSwede).isNotEmpty()
    }

    @Test
    fun switzerlandAndCanadaServeTheirSecondLanguage() = runBlocking {
        // Swiss rows carry de/fr/it names and CNF rows are English-named with French aliases.
        val swissGerman = searchCurrent("milch", 5, langs("de"), "swiss")
        val swissFrench = searchCurrent("lait", 5, langs("fr"), "swiss")
        assertThat(swissGerman).isNotEmpty()
        assertThat(swissFrench).isNotEmpty()

        val francophoneCanadian = searchCurrent("lait", 5, langs("fr"), "cnf")
        assertThat(francophoneCanadian).isNotEmpty()
        assertThat(francophoneCanadian.all { it.food.source == "cnf" }).isTrue()
    }

    @Test
    fun restoredNationalRowsPreserveStapleWinnersAndRemainReachable() = runBlocking {
        val lentils = search("lentils", 10, "en", "cnf")
        assertThat(lentils.first().name).isEqualTo("Lentils, mature seeds, dry")
        assertThat(lentils.map { it.name }).contains("Lentils, sprouted, raw")
        val rice = search("ris", 10, "da", "frida")
        assertThat(rice.first().name).isEqualTo("Ris, polerede, rå")
        assertThat(rice.map { it.name }).contains("Ris, brune, rå")
        assertThat(search("gurka", 5, "sv", "livsmedel").first().name).isEqualTo("Gurka")
        assertThat(search("kurkku", 5, "fi", "fineli").first().name).isEqualTo("Kurkku")
        assertThat(search("sprouted lentils", 10, "en", "cnf").map { it.name })
            .contains("Lentils, sprouted, raw")
    }

    @Test
    fun searchWithAFallbackShelfStaysWithinTheLatencyBudget() = runBlocking {
        // The fallback query runs on every search with a national source, a second FTS query per
        // keystroke. The bound is loose; the useful output is the logged median.
        val terms = listOf("melk", "brot", "leche", "tomate", "ost", "banan", "ris", "smor")
        repeat(2) { for (term in terms) searchCurrent(term, 30, langs("nb"), "matvaretabellen") }

        val bothShelves =
            terms.map { term ->
                val started = System.nanoTime()
                searchCurrent(term, 30, langs("nb"), "matvaretabellen")
                (System.nanoTime() - started) / 1_000_000.0
            }
        // Each query on its own, since the whole-search number mixes in the branded query and the
        // merge.
        fun medianOf(block: suspend (String) -> Unit): Double {
            val ms =
                terms.map { term ->
                    val started = System.nanoTime()
                    runBlocking { block(term) }
                    (System.nanoTime() - started) / 1_000_000.0
                }
            return ms.sorted()[ms.size / 2]
        }
        val priority = SettingsManager.foodLanguagePriority(langs("nb"))
        val primaryMedian = medianOf { term ->
            dao()
                .searchPrimarySource(
                    FtsQuery.sanitize(term)!!,
                    foldForSearch(term),
                    langs("nb"),
                    priority,
                    "matvaretabellen",
                    "",
                    "",
                    30,
                )
        }
        val brandedMedian = medianOf { term ->
            dao().searchBranded(FtsQuery.sanitize(term)!!, foldForSearch(term), 30)
        }
        // The "only this source" path: primary and branded, no fallback.
        val restrictedOnly =
            terms.map { term ->
                val started = System.nanoTime()
                val primary =
                    dao()
                        .searchPrimarySource(
                            FtsQuery.sanitize(term)!!,
                            foldForSearch(term),
                            langs("nb"),
                            priority,
                            "matvaretabellen",
                            "",
                            "",
                            30,
                        )
                if (primary.size < 30) {
                    dao()
                        .searchBranded(
                            FtsQuery.sanitize(term)!!,
                            foldForSearch(term),
                            30 - primary.size,
                        )
                }
                (System.nanoTime() - started) / 1_000_000.0
            }
        val restrictedMedian = restrictedOnly.sorted()[restrictedOnly.size / 2]
        val median = bothShelves.sorted()[bothShelves.size / 2]
        android.util.Log.i(
            "SearchLatency",
            "toggle ON (one source) median " + String.format("%.1f", restrictedMedian),
        )
        android.util.Log.i(
            "SearchLatency",
            "whole search ms: " +
                bothShelves.joinToString(", ") { String.format("%.1f", it) } +
                " -- median " +
                String.format("%.1f", median) +
                " | primary " +
                String.format("%.1f", primaryMedian) +
                " | branded " +
                String.format("%.1f", brandedMedian),
        )

        assertThat(median).isLessThan(750.0)
        Unit
    }

    @Test
    fun emptyNationalSourceSearchStillReturnsGenericFoods() = runBlocking {
        // The out-of-the-box state: countryPreference defaults to SYSTEM, and any device country
        // outside NATIONAL_TABLE_SOURCE resolves to "". That must mean "every generic source",
        // not "no generic source" -- the latter left a default-configuration reader with nothing
        // but branded OFF rows for ordinary foods.
        val results =
            dao()
                .searchPrimarySource(
                    FtsQuery.sanitize("butter")!!,
                    foldForSearch("butter"),
                    langs("en"),
                    PRIORITY_EN,
                    "",
                    "",
                    "",
                    20,
                )
                .map { it.result.food }

        assertThat(results).isNotEmpty()
        assertThat(results.all { it.source != "off" }).isTrue()
        // With no national source, many generic tables compete. Which ones win is the ranking's
        // business and changes with every table added.
        assertThat(results.map { it.source }.distinct().size).isAtLeast(3)
        Unit
    }

    @Test
    fun fallbackSearchRetainsAliasAndCommaQualifierRanking() = runBlocking {
        // The fallback shelf keeps the alias ladder and word-boundary rungs. A usda reader reaches
        // BLS's "Süßmolke" only through fallback, and the exact rung must put it ahead of
        // "Süßmolkenpulver", which merely starts with the same word.
        val aliasResults =
            searchFallback("sussmolke", limit = 5, nationalSource = "usda", language = "de")
        assertThat(aliasResults).isNotEmpty()
        assertThat(aliasResults.first().displayName).isEqualTo("Süßmolke")
        assertThat(aliasResults.first().food.source).isEqualTo("bls")

        // And the comma rung: national tables write "head, qualifier", so "butter salted" must
        // reach "Butter salted" rather than a dish that merely mentions both words.
        val qualified = searchFallback("butter salted", limit = 5, nationalSource = "usda")
        assertThat(qualified).isNotEmpty()
        assertThat(qualified.first().food.name.lowercase()).contains("butter")
    }

    @Test
    fun borrowingReachesAnotherTableOnAGenuineZeroRowMiss() = runBlocking {
        // Borrowing end to end. If TableLanguages' query lets a bare `source` resolve to the
        // inner table, every source looks empty and borrowing silently does nothing. Swiss has no
        // sweet whey, so it must borrow from BLS rather than return only branded products.
        val lenders = tableLanguages.sourcesServing(listOf("de"), excluding = "swiss")
        assertThat(lenders).contains("bls")

        val results =
            searchCurrent(
                term = "Süßmolke",
                limit = 20,
                languages = tableLanguages.forSource("swiss"),
                nationalSource = "swiss",
            )
        assertThat(results).isNotEmpty()
        assertThat(results.first().food.source).isEqualTo("bls")
    }

    @Test
    fun searchResultsAreNotFullOfIdenticalDuplicates() = runBlocking {
        // Open Food Facts lists the same product under many barcodes — 87 rows all called
        // "Tomato Ketchup" by "Heinz". Left in, this is exactly the duplicate-entry complaint
        // people make about MyFitnessPal.
        val results = search("ketchup", limit = 25)

        // Display groups are source-local: equal names from distinct national tables can have
        // different declared nutrition. OFF package siblings still share one display winner.
        val identical =
            results.map {
                "${it.source}|${it.name.lowercase()}|${it.brand?.lowercase().orEmpty()}|${(it.kcal100g / 50).toInt()}"
            }
        assertThat(identical).containsNoDuplicates()

        // Repeats of a name at different energies are NOT duplicates and must survive:
        // Heinz sells ketchup at 44, 64 and 100 kcal/100g with different carbohydrate content,
        // and the energy shown on each row is what tells them apart. What must not happen is one
        // product crowding out the results.
        results
            .groupBy { "${it.source}|${it.name.lowercase()}|${it.brand?.lowercase().orEmpty()}" }
            .forEach { (label, group) ->
                assertThat(group.size).isLessThan(5)
                assertThat(group.map { (it.kcal100g / 50).toInt() }).containsNoDuplicates()
                assertThat(label).isNotEmpty()
            }
    }

    @Test
    fun everyResultCarriesUsableNutritionData() = runBlocking {
        val results = search("bread", limit = 30)
        assertThat(results).isNotEmpty()
        results.forEach { food ->
            assertThat(food.name).isNotEmpty()
            assertThat(food.kcal100g).isAtLeast(0.0)
            assertThat(food.kcal100g).isAtMost(900.0)
            assertThat(food.protein100g).isAtLeast(0.0)
            assertThat(food.source).isNotEmpty()
        }
    }

    @Test
    fun energyAndMacrosAgree() = runBlocking {
        // A database that stores macros x100 as integers reads Nutella as proteins_100g = 541.
        // Anything like that would fail here.
        val results = search("chocolate", limit = 50)
        assertThat(results).isNotEmpty()
        results.forEach { food ->
            val predicted = food.protein100g * 4 + food.fat100g * 9 + food.carbs100g * 4
            val allowed = maxOf(35.0, 0.30 * food.kcal100g)
            assertThat(Math.abs(food.kcal100g - predicted)).isAtMost(allowed)
        }
    }

    @Test
    fun barcodeLookupResolvesAKnownProduct() = runBlocking {
        // A brand-only term. Generic lab-measured foods now rank first and carry no
        // barcode, so a generic word like "milk" legitimately returns 50 barcode-less rows,
        // which is correct behaviour.
        val sample = search("nutella", limit = 50).firstOrNull { !it.barcode.isNullOrBlank() }
        assertThat(sample).isNotNull()

        val found = dao().findByBarcode(sample!!.barcode!!, langs("en"), PRIORITY_EN)
        assertThat(found).isNotNull()
        assertThat(found!!.food.id).isEqualTo(sample.id)
    }

    @Test
    fun aBarcodeLostToTheDuplicateCollapseStillResolves() = runBlocking {
        // Real package codes remain reachable, and each keeps its own record, even when only one is
        // selected for typed search.
        val canonical = "42105220"
        val collapsed = "5449000028921"

        val a = dao().findByBarcode(canonical, langs("en"), PRIORITY_EN)
        val b = dao().findByBarcode(collapsed, langs("en"), PRIORITY_EN)
        assertThat(a).isNotNull()
        assertThat(b).isNotNull()

        assertThat(a!!.food.barcode).isEqualTo(canonical)
        assertThat(b!!.food.barcode).isEqualTo(collapsed)
        assertThat(b.food.id).isNotEqualTo(a.food.id)
        assertThat(dao().getById(b.food.id, langs("en"), PRIORITY_EN)!!.food).isEqualTo(b.food)
    }

    @Test
    fun getByIdReturnsNullRatherThanThrowingForAFoodThatDoesNotExist() = runBlocking {
        // A favourite or diary entry can point at an id a rebuild dropped. The display-name LEFT
        // JOIN must not turn "no such row" into a crash or a phantom match.
        val impossibleId = 999_999_999_999L
        assertThat(dao().getById(impossibleId, langs("en"), PRIORITY_EN)).isNull()
        assertThat(dao().findByBarcode("0000000000000", langs("en"), PRIORITY_EN)).isNull()
    }

    @Test
    fun aHiddenBarcodeVariantKeepsItsOwnLabelNutrition() = runBlocking {
        // Pinned OFF export: this peanut butter was redirected to a display sibling with
        // 552.55 kcal and 33.87 g carbohydrate. Its own label is 598 kcal and 8.5 g.
        val code = "0000977790904"
        val found = dao().findByBarcode(code, langs("en"), PRIORITY_EN)!!.food
        assertThat(found.barcode).isEqualTo(code)
        assertThat(found.kcal100g).isEqualTo(598.0)
        assertThat(found.carbs100g).isEqualTo(8.5)
        assertThat(dao().getById(found.id, langs("en"), PRIORITY_EN)!!.food).isEqualTo(found)
        assertThat(search("Burro di arachidi", limit = 50).map { it.id }).doesNotContain(found.id)
    }

    @Test
    fun zeroCalorieFoodsSurvivedTheQualityFilter() = runBlocking {
        // A 1 kcal minimum silently discarded water, black coffee and tea -- things people log.
        val results = search("water", limit = 50)
        assertThat(results.any { it.kcal100g == 0.0 }).isTrue()
    }

    @Test
    fun namedServingsAreAvailableForSomeFoods() = runBlocking {
        // Servings are what let someone log "1 slice" instead of doing arithmetic.
        val results = search("bread", limit = 50)
        val withServings = results.count { dao().servingsFor(it.id).isNotEmpty() }
        assertThat(withServings).isGreaterThan(0)
    }

    @Test
    fun anAccentedNameIsFoundByTypingItWithoutAccents() = runBlocking {
        // SQLite's LOWER() folds ASCII only, so before name_folded every accented record fell to
        // the bottom ranking tier no matter how well it matched. This was never a future problem:
        // 91 rows in the English-only database already carry accents, and "pâté" and "sautéed"
        // were both unreachable this way.
        val results = search("pate", limit = 30)
        assertThat(results.any { it.name.contains("pâté", ignoreCase = true) }).isTrue()

        val sauteed = search("sauteed", limit = 30)
        assertThat(sauteed.any { it.name.contains("sautéed", ignoreCase = true) }).isTrue()
    }

    @Test
    fun genericFoodsDeclareTheirLanguageAndBrandedOnesDoNot() = runBlocking {
        // Open Food Facts names have no language column, so their `lang` is null. Generic rows
        // always declare one, though not always English (TCA is Portuguese).
        val results = search("milk", limit = 50) + search("nutella", limit = 50)
        assertThat(results).isNotEmpty()
        results.forEach { food ->
            if (food.source == "off") {
                assertThat(food.lang).isNull()
            } else {
                assertThat(food.lang).isNotNull()
                assertThat(food.lang).isNotEmpty()
            }
        }
    }

    @Test
    fun sourceRankSeparatesLabMeasuredFoodsFromPackagedOnes() = runBlocking {
        // Replaces a hardcoded `source IN ('usda','ciqual')` in the query. The point of moving it
        // into a column is that a national table added later cannot be silently left ranking
        // below packaged products, so the column and the source must agree.
        val results = search("chocolate", limit = 50)
        assertThat(results).isNotEmpty()
        results.forEach { food ->
            val expected = if (food.source == "off") 1 else 0
            assertThat(food.sourceRank).isEqualTo(expected)
        }
        // And the ordering actually applies it: generic foods cannot appear after branded ones.
        val ranks = results.map { it.sourceRank }
        assertThat(ranks).isInOrder()
    }

    @Test
    fun displayNameShowsTheReadersLanguageAliasWhenOneExists() = runBlocking {
        // Test display resolution independently of search ordering. Search ranking has its own
        // relevance contract; tying this assertion to the first result made a pre-existing ranking
        // regression look like an alias/display failure.
        val milk =
            dao()
                .getById(
                    100000000915L,
                    langs("es"),
                    SettingsManager.foodLanguagePriority(langs("es")),
                )
        assertThat(milk).isNotNull()
        // BEDCA's row is stored in Spanish and English is its alias. Both directions are asserted,
        // because the point of the display join is that the reader's language wins whichever way
        // round the row happens to be.
        assertThat(milk!!.food.name).isEqualTo("Leche, desnatada, pasteurizada")
        assertThat(milk.displayName).isEqualTo("Leche, desnatada, pasteurizada")
        val englishReader =
            dao()
                .getById(
                    100000000915L,
                    langs("en"),
                    SettingsManager.foodLanguagePriority(langs("en")),
                )
        assertThat(englishReader!!.displayName).isEqualTo("Milk, skimmed, pasteurized")

        // The search itself still must find a Spanish milk result; the ranking benchmark decides
        // which acceptable milk variant should be first.
        val results =
            searchCurrent("leche", limit = 5, languages = langs("es"), nationalSource = "bedca")
        assertThat(results.first().food.id).isEqualTo(100000000915L)
        assertThat(results.first().displayName).isEqualTo("Leche, desnatada, pasteurizada")

        // The row is Spanish and English is the alias, so an English reader gets a substitution
        // rather than a fall-through to `name` -- the mirror image of the Spanish reader above.
        val english = searchCurrent("leche", limit = 5, languages = langs("en"))
        assertThat(english.first().displayName).isEqualTo("Milk, skimmed, pasteurized")
        assertThat(english.first().food.name).isEqualTo("Leche, desnatada, pasteurizada")
    }

    @Test
    fun theFoodNameLanguageListPicksTheFirstLanguageTheFoodActuallyHas() = runBlocking {
        // The Swiss publisher supplies this row's German name and French/Italian/English aliases.
        // Use source-owned translations, not aliases folded into Ciqual
        // from a different national table by global display collapse.
        val peanutOil = 160_000_000_596L

        suspend fun nameUnder(vararg languages: String): String? {
            val list = langs(*languages)
            return dao()
                .getById(peanutOil, list, SettingsManager.foodLanguagePriority(list))
                ?.displayName
        }

        assertThat(nameUnder("fr")).isEqualTo("Huile d'arachide")
        assertThat(nameUnder("de")).isEqualTo("Erdnussöl")
        assertThat(nameUnder("it")).isEqualTo("Olio di arachidi")

        // The list is a priority: the first entry the food HAS a name in wins. Not the first
        // entry outright, which would fall back to English the moment the top choice happened
        // not to exist for that particular food.
        assertThat(nameUnder("de", "fr")).isEqualTo("Erdnussöl")
        assertThat(nameUnder("fr", "de")).isEqualTo("Huile d'arachide")

        // A language this food does not have falls through to the next one rather than to the
        // English name -- the case that makes it a priority list rather than a single choice
        // with extra steps.
        assertThat(nameUnder("en-GB", "fr")).isEqualTo("Huile d'arachide")

        // And with nothing enabled that this food has, the stored English name.
        assertThat(nameUnder("cs")).isEqualTo("Peanut oil")
        val ciqual =
            dao()
                .getById(
                    100_017_040L,
                    langs("de"),
                    SettingsManager.foodLanguagePriority(langs("de")),
                )
        assertThat(ciqual?.displayName).isEqualTo("Peanut oil")
    }

    @Test
    fun addingLanguagesToTheListNeverChangesWhatSearchFinds() = runBlocking {
        // Adding languages may reorder results, but must never make a food unreachable: everything
        // findable with one language is still findable with four.
        listOf("milk", "leche", "brot", "huile", "honey", "aceite").forEach { term ->
            val default =
                searchCurrent(term, limit = 50, languages = langs("en")).map { it.food.id }
            val wide =
                searchCurrent(term, limit = 200, languages = langs("de", "fr", "es", "en-GB")).map {
                    it.food.id
                }
            assertThat(wide).containsAtLeastElementsIn(default)
        }
    }

    @Test
    fun brandedFoodsAlwaysDisplayTheirOwnStoredNameRegardlessOfLanguage() = runBlocking {
        // Branded rows carry no aliases, so a scanned foreign product shows the name Open Food
        // Facts stored for it, whatever the reader's language setting.
        listOf("es", "de", "fr", "nb").forEach { language ->
            val results = searchCurrent("chocolate", limit = 50, languages = langs(language))
            results
                .filter { it.food.source == "off" }
                .forEach { off -> assertThat(off.displayName).isEqualTo(off.food.name) }
        }
    }

    @Test
    fun anUnsupportedInterfaceLanguageStillGetsEnglishResults() = runBlocking {
        // Czech has an interface but no food names, so names fall back to English rather than to
        // whichever table happened to win.
        val czech = searchCurrent("milk", limit = 10, languages = langs("cs"))
        assertThat(czech).isNotEmpty()
        val top = czech.first()
        assertThat(top.displayName.lowercase()).contains("milk")
        assertThat(top.food.source).isNotEqualTo("off")
    }

    // ---- the word is in the query, and nothing in the build can reach it ----

    @Test
    fun aCompoundQueryReachesTheFoodItsTableFilesSeparately() = runBlocking {
        // `svinekjøtt` is an ordinary Norwegian word for pork and returned ZERO ROWS -- not a bad
        // ranking, an empty screen -- because Norway files pork as `Svin, nakkekoteletter` and
        // FTS5 has no leading wildcard to reach `svin` with.
        val results =
            searchCurrent(
                "svinekjøtt",
                limit = 5,
                languages = langs("nb"),
                nationalSource = "matvaretabellen",
            )
        assertThat(results).isNotEmpty()
        assertThat(results.first().food.name.lowercase()).startsWith("svin")
        assertThat(results.first().food.source).isEqualTo("matvaretabellen")
    }

    @Test
    fun aCompoundQueryIsRescuedEvenWhenSomethingElseMatchedIt() = runBlocking {
        // The reason the trigger is "no head match" and not "no rows". `storfekjøtt` DID return
        // something: *Storfe, fett fra storfekjøtt, rå* -- beef FAT -- whose name happens to
        // contain the whole compound. A zero-rows test would leave the reader with the fat.
        val results =
            searchCurrent(
                "storfekjøtt",
                limit = 5,
                languages = langs("nb"),
                nationalSource = "matvaretabellen",
            )
        assertThat(results.first().food.name).isEqualTo("Storfe, kjøtt, rå")
    }

    @Test
    fun theHeadWordArmPicksTheCutAndNotTheShortestName() = runBlocking {
        // The arm's whole reason to exist. Searching the modifier `kylling` alone returns whole
        // chicken and chicken SKIN first, because the shortest name wins a tie the tier ladder
        // cannot break. Preferring rows that also contain the head word `filet` fixes it -- and it
        // has to sit BELOW the tier, or `svinekjøtt` returns *Villsvin* (wild boar) at #1.
        val results =
            searchCurrent(
                "kyllingfilet",
                limit = 5,
                languages = langs("nb"),
                nationalSource = "matvaretabellen",
            )
        assertThat(results.first().food.name).isEqualTo("Kylling, filet, uten skinn, rå")
    }

    @Test
    fun aPluralReachesTheSingularItsTableStores() = runBlocking {
        // FTS5 prefix matching is one-directional: `"linssit"*` can never reach `Linssi`. Six of
        // eighteen natural plural queries returned nothing at all before this.
        val finnish =
            searchCurrent("linssit", limit = 5, languages = langs("fi"), nationalSource = "fineli")
        assertThat(finnish).isNotEmpty()
        assertThat(finnish.first().food.name.lowercase()).startsWith("linssi")

        val spanish =
            searchCurrent("lentejas", limit = 5, languages = langs("es"), nationalSource = "bedca")
        assertThat(spanish.first().food.name.lowercase()).startsWith("lenteja")
        assertThat(spanish.first().food.source).isEqualTo("bedca")
    }

    @Test
    fun theNordicUmlautPluralIsRescuedToo() = runBlocking {
        // morötter folds to morotter, whose singular no `s` or `t` rule reaches.
        val results =
            searchCurrent(
                "morötter",
                limit = 5,
                languages = langs("sv"),
                nationalSource = "livsmedel",
            )
        assertThat(results).isNotEmpty()
        assertThat(results.first().food.name.lowercase()).startsWith("morot")
    }

    @Test
    fun aStemTheDataDoesNotHaveIsNeverInvented() = runBlocking {
        // Restored BEDCA has Zanahoria. Use an absent stem to test that the rescue
        // requires a word in the real catalogue vocabulary instead of inventing a singular.
        assertThat(lexicon.lexicons().getValue("bedca").words).doesNotContain("qzvunknownfood")
        val results =
            searchCurrent(
                "qzvunknownfoods",
                limit = 5,
                languages = langs("es"),
                nationalSource = "bedca",
            )
        assertThat(results).isEmpty()
        val carrots = searchCurrent("zanahorias", 5, langs("es"), "bedca")
        assertThat(carrots.first().food.source).isEqualTo("bedca")
        assertThat(carrots.first().food.name).startsWith("Zanahoria")
    }

    @Test
    fun theRescueLeavesAnAnsweredQueryCompletelyAlone() = runBlocking {
        // On the device rather than in the replica: a query the reader's own table answers must
        // be left alone by the rescue. Each of these could plausibly be disturbed by it.
        assertThat(
                searchCurrent(
                        "melk",
                        limit = 5,
                        languages = langs("nb"),
                        nationalSource = "matvaretabellen",
                    )
                    .first()
                    .food
                    .name
            )
            .isEqualTo("Melk, uspesifisert")
        assertThat(
                searchCurrent(
                        "linser",
                        limit = 5,
                        languages = langs("nb"),
                        nationalSource = "matvaretabellen",
                    )
                    .first()
                    .food
                    .name
                    .lowercase()
            )
            .startsWith("linser")
        assertThat(
                searchCurrent("leche", limit = 5, languages = langs("es"), nationalSource = "bedca")
                    .first()
                    .food
                    .name
                    .lowercase()
            )
            .startsWith("leche")
    }

    @Test
    fun theLexiconAssetIsBundledAndCoversEveryNationalTable() = runBlocking {
        val lexicons = lexicon.lexicons()
        // Thirteen national tables, keyed per source, not per language, so the four
        // English-named tables carry four separate vocabularies rather than one union.
        assertThat(lexicons.keys)
            .containsAtLeast(
                "usda",
                "cofid",
                "cnf",
                "afcd",
                "ciqual",
                "bls",
                "bedca",
                "matvaretabellen",
                "livsmedel",
                "frida",
                "fineli",
                "swiss",
                "tca",
            )
        assertThat(lexicons.getValue("matvaretabellen").lang).isEqualTo("nb")
        assertThat(lexicons.getValue("matvaretabellen").words).contains("svin")
        assertThat(lexicons.getValue("bedca").words).contains("lenteja")
        // Measured 2026-08-29 against build 10: Frida writes pork as Flæsk and Gris and never
        // writes `svin` alone, which is why `svinekød` cannot be split at ANY head threshold.
        assertThat(lexicons.getValue("frida").words).doesNotContain("svin")
        // Against the shipped asset rather than a hand-written set: USDA carries the plural, AFCD
        // does not, and keyed per language that union would refuse an Australian reader
        // `lentils` -> `lentil`.
        assertThat(lexicons.getValue("usda").words).contains("lentils")
        assertThat(lexicons.getValue("afcd").words).doesNotContain("lentils")
        assertThat(lexicons.getValue("afcd").words).contains("lentil")
    }

    @Test
    fun theRescueCostsOneExtraQueryAndOnlyWhenItFires() = runBlocking {
        // What the rescue costs, measured rather than assumed. A rescued search runs the primary
        // query TWICE plus the one-off lexicon read; a search the reader's table answers runs it
        // once and never opens the asset at all.
        val rescued = listOf("svinekjøtt", "storfekjøtt", "kyllingfilet")
        val answered = listOf("melk", "linser", "egg")

        fun medianMs(terms: List<String>, block: suspend (String) -> Unit): Double = runBlocking {
            terms.forEach { block(it) } // warm the page cache, as the existing budget test does
            val samples =
                terms
                    .map { term ->
                        val started = System.nanoTime()
                        block(term)
                        (System.nanoTime() - started) / 1_000_000.0
                    }
                    .sorted()
            samples[samples.size / 2]
        }

        val lexiconStarted = System.nanoTime()
        lexicon.lexicons()
        val lexiconMs = (System.nanoTime() - lexiconStarted) / 1_000_000.0

        val search: suspend (String) -> Unit = { term ->
            searchCurrent(
                term,
                limit = 30,
                languages = langs("nb"),
                nationalSource = "matvaretabellen",
            )
            Unit
        }
        val rescuedMedian = medianMs(rescued, search)
        val answeredMedian = medianMs(answered, search)

        android.util.Log.i(
            "SearchLatency",
            "rescue: lexicon load " +
                String.format("%.1f", lexiconMs) +
                " ms (once) | rescued median " +
                String.format("%.1f", rescuedMedian) +
                " ms | answered median " +
                String.format("%.1f", answeredMedian) +
                " ms",
        )

        // The same budget the whole-search test uses. The point of the assertion is that a
        // rescued search stays inside it, not that it is free -- it is a second query.
        assertThat(rescuedMedian).isLessThan(750.0)
        Unit
    }
}
