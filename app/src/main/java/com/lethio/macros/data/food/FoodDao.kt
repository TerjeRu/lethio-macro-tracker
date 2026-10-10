package com.lethio.macros.data.food

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.SkipQueryVerification
import com.lethio.macros.data.food.entity.DatabaseMetaEntity
import com.lethio.macros.data.food.entity.FoodEntity
import com.lethio.macros.data.food.entity.FoodServingEntity

/**
 * A food with the name to show: the alias in the reader's language when one exists, otherwise
 * [FoodEntity.name]. Kept out of [FoodEntity] because it depends on who is asking.
 */
data class FoodWithDisplayName(
    @Embedded val food: FoodEntity,
    @ColumnInfo(name = "display_name") val displayName: String,
)

/** A generic hit with its match tier, so [composeSearchResults] can merge shelves by match quality. */
data class RankedFood(
    @Embedded val result: FoodWithDisplayName,
    @ColumnInfo(name = "match_tier") val matchTier: Int,
)

/**
 * The food's name in the first of the reader's languages it has one in.
 *
 * `foods.name` is itself in a language (French on Ciqual, German on BLS), so an alias wins only if
 * the reader ranks its language above the row's own. `INSTR(...) = 0` means the reader did not ask
 * for the row's language, and then any requested alias beats the stored name.
 */
private const val DISPLAY_NAME = """COALESCE((
            SELECT fa.name FROM food_aliases fa
            WHERE fa.food_id = f.id AND fa.lang IN (:languages)
              AND (f.lang IS NULL
                   OR INSTR(:languagePriority, ',' || f.lang || ',') = 0
                   OR INSTR(:languagePriority, ',' || fa.lang || ',')
                      < INSTR(:languagePriority, ',' || f.lang || ','))
            ORDER BY INSTR(:languagePriority, ',' || fa.lang || ',')
            LIMIT 1
        ), f.name) AS display_name"""

/**
 * Tiers 0-1 for the alias in the reader's own language.
 *
 * `alias_names` anchors only its first alias, so a later one (the French name on a German-named
 * Swiss row) could never reach a head tier. Not tier 2: an English gloss of a foreign compound
 * ("Butter rusk" for Butterzwieback) has the shape of a real qualifier and would win a bare
 * `butter`. Returns 5 when no alias qualifies, so it can only improve a row's tier.
 */
private const val READER_ALIAS_TIER = """
                CASE
                    WHEN (SELECT fa.name FROM food_aliases fa
                          WHERE fa.food_id = f.id AND fa.lang IN (:languages)
                          ORDER BY INSTR(:languagePriority, ',' || fa.lang || ',') LIMIT 1)
                         IN (:plainQuery, :plainQuery || 's') THEN 0
                    WHEN (SELECT fa.name FROM food_aliases fa
                          WHERE fa.food_id = f.id AND fa.lang IN (:languages)
                          ORDER BY INSTR(:languagePriority, ',' || fa.lang || ',') LIMIT 1)
                         LIKE :plainQuery || ',%'
                      OR (SELECT fa.name FROM food_aliases fa
                          WHERE fa.food_id = f.id AND fa.lang IN (:languages)
                          ORDER BY INSTR(:languagePriority, ',' || fa.lang || ',') LIMIT 1)
                         LIKE :plainQuery || 's,%' THEN 1
                    ELSE 5
                END"""

/**
 * The name's first word is one of the typed words.
 *
 * NEVO and FOODfiles name head-first and stack qualifiers ("Rijst witte gekookt", "Oil, olive"), so
 * multi-word queries rarely match as a phrase. Worth tier 3, never a head tier. Active only when
 * `:leadWordSource` is the row's table, which the repository sets for [LEAD_WORD_SOURCES] on the
 * primary shelf.
 */
private const val LEAD_WORD_HEAD =
    "SUBSTR(REPLACE(f.name_folded, ',', ' '), 1, INSTR(REPLACE(f.name_folded, ',', ' ') || ' ', ' ') - 1)"

private const val LEAD_WORD_MATCH = """(:leadWordSource <> '' AND f.source = :leadWordSource
                    AND INSTR(:plainQuery, ' ') > 0
                    AND (INSTR(' ' || :plainQuery || ' ', ' ' || """ + LEAD_WORD_HEAD + """ || ' ') > 0
                      OR (""" + LEAD_WORD_HEAD + """ LIKE '%s' AND INSTR(' ' || :plainQuery || ' ',
                          ' ' || SUBSTR(""" + LEAD_WORD_HEAD + """, 1, LENGTH(""" + LEAD_WORD_HEAD + """) - 1) || ' ') > 0)
                      OR (""" + LEAD_WORD_HEAD + """ LIKE '%en' AND INSTR(' ' || :plainQuery || ' ',
                          ' ' || SUBSTR(""" + LEAD_WORD_HEAD + """, 1, LENGTH(""" + LEAD_WORD_HEAD + """) - 2) || ' ') > 0)))"""

/**
 * Orders tier 3 after [LEAD_WORD_MATCH]: names the food and contains the phrase ("Sugar, white"),
 * then names the food ("Oil, olive"), then only contains it. Parentheses count as separators.
 */
private const val LEAD_WORD_ARM = """
            CASE
                WHEN """ + LEAD_WORD_MATCH + """
                 AND ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' '), '(', ' '), ')', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
                     LIKE '% ' || :plainQuery || ' %' THEN 0
                WHEN """ + LEAD_WORD_MATCH + """ THEN 1
                ELSE 2
            END"""

/**
 * The tier ladder, read by `ORDER BY` and returned as `match_tier`.
 *
 * 0 exact, 1 comma-qualified, 2 space-qualified, 3 whole word anywhere, 4 partial, 5 none. The
 * repeated `REPLACE('  ', ' ')` is needed because `REPLACE` is single-pass. A space-qualified alias
 * scores 3, not 2, for the gloss reason in [READER_ALIAS_TIER]. A `search_extra` (compound head)
 * match scores 3 at most, so a compound never outranks the plain food.
 */
private const val GENERIC_MATCH_TIER = """MIN(
                CASE
                    WHEN f.name_folded = :plainQuery OR f.name_folded = :plainQuery || 's' THEN 0
                    WHEN f.name_folded LIKE :plainQuery || ',%'
                      OR f.name_folded LIKE :plainQuery || 's,%' THEN 1
                    WHEN f.name_folded LIKE :plainQuery || ' %'
                      OR f.name_folded LIKE :plainQuery || 's %' THEN 2
                    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
                         LIKE '% ' || :plainQuery || ' %' THEN 3
                    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
                         LIKE '% ' || :plainQuery || '%' THEN 4
                    ELSE 5
                END,
                CASE
                    WHEN f.alias_names = :plainQuery OR f.alias_names = :plainQuery || 's' THEN 0
                    WHEN f.alias_names LIKE :plainQuery || ',%'
                      OR f.alias_names LIKE :plainQuery || 's,%' THEN 1
                    WHEN f.alias_names LIKE :plainQuery || ' %'
                      OR f.alias_names LIKE :plainQuery || 's %' THEN 3
                    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.alias_names, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
                         LIKE '% ' || :plainQuery || ' %' THEN 3
                    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.alias_names, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
                         LIKE '% ' || :plainQuery || '%' THEN 4
                    ELSE 5
                END,
                CASE
                    WHEN f.search_extra IS NULL THEN 5
                    WHEN f.search_extra = :plainQuery
                      OR f.search_extra LIKE :plainQuery || ' %'
                      OR f.search_extra LIKE '% ' || :plainQuery || ' %'
                      OR f.search_extra LIKE '% ' || :plainQuery THEN 3
                    ELSE 5
                END,
                """ + READER_ALIAS_TIER + """,
                CASE WHEN """ + LEAD_WORD_MATCH + """ THEN 3 ELSE 5 END
            )"""

/** Shared front of the generic queries; they differ only in which sources are in scope. */
private const val GENERIC_SEARCH_HEAD = """
        SELECT f.*, """ + DISPLAY_NAME + """,
               """ + GENERIC_MATCH_TIER + """ AS match_tier
        FROM food_search s
        JOIN foods f ON f.id = s.rowid
        WHERE food_search MATCH :ftsQuery
          AND f.source <> 'off'
          AND """

/**
 * For a split compound, prefer rows whose name contains the head word (`kjøtt` in `svinekjøtt`).
 * It must sit below the tier ladder: above it, *Villsvin, kjøtt* beats *Svin, mørbrad*.
 *
 * The `:head = ''` branch is required. An empty head becomes `LIKE '%  %'`, which matches every
 * flattened ", " and would reorder every unsplit query.
 */
private const val HEAD_WORD_ARM = """
            CASE
                WHEN :head = '' THEN 0
                WHEN ' ' || REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' ') || ' '
                     LIKE '% ' || :head || ' %' THEN 0
                ELSE 1
            END"""

/**
 * The generic ranking ladder: native-language head match, tier, lead-word order, preferred group
 * member, head word, `basic_rank`, word count, length, bm25, quality.
 *
 * `search_extra` has bm25 weight 0.0: it makes a row match, it does not decide which one wins.
 * Measure against the search fixtures (`tools/export_search_results.py`) before changing an arm.
 */
private const val GENERIC_SEARCH_ORDER = """
        ORDER BY
            CASE
                WHEN f.lang IN (:languages)
                 AND f.lang <> 'en'
                 AND (f.name_folded = :plainQuery OR f.name_folded = :plainQuery || 's'
                   OR f.name_folded LIKE :plainQuery || ',%'
                   OR f.name_folded LIKE :plainQuery || 's,%'
                   OR f.name_folded LIKE :plainQuery || ' %'
                   OR f.name_folded LIKE :plainQuery || 's %'
                   OR f.alias_names = :plainQuery OR f.alias_names = :plainQuery || 's'
                   OR f.alias_names LIKE :plainQuery || ',%'
                   OR f.alias_names LIKE :plainQuery || 's,%'
                   OR f.alias_names LIKE :plainQuery || ' %'
                   OR f.alias_names LIKE :plainQuery || 's %') THEN 0
                ELSE 1
            END,
            """ + GENERIC_MATCH_TIER + """,
            """ + LEAD_WORD_ARM + """,
            COALESCE((SELECT 1 - preferred FROM food_search_members WHERE food_id = f.id), 0),
            """ + HEAD_WORD_ARM + """,
            f.basic_rank,
            LENGTH(f.name) - LENGTH(REPLACE(f.name, ' ', '')),
            LENGTH(f.name),
            bm25(food_search, 5.0, 1.0, 3.0, 0.0),
            f.quality_score DESC
        LIMIT :limit
        """

/** One (source, language) pair from [FoodDao.languagesBySource]. */
data class SourceLanguage(val source: String, val lang: String)

@Dao
interface FoodDao {

    /** The primary shelf: the selected national table, or every generic source when it is empty. */
    @SkipQueryVerification
    @Query(GENERIC_SEARCH_HEAD + "(:nationalSource = '' OR f.source = :nationalSource)" + GENERIC_SEARCH_ORDER)
    suspend fun searchPrimarySource(
        ftsQuery: String,
        plainQuery: String,
        languages: List<String>,
        languagePriority: String,
        nationalSource: String,
        head: String,
        leadWordSource: String,
        limit: Int,
    ): List<RankedFood>

    /** Searches branded Open Food Facts products independently of generic-food ranking. */
    @SkipQueryVerification
    @Query(
        """
        SELECT f.*, f.name AS display_name FROM food_search s
        JOIN foods f ON f.id = s.rowid
        WHERE food_search MATCH :ftsQuery AND f.source = 'off'
        ORDER BY
            CASE WHEN f.name_folded = :plainQuery THEN 0
                 WHEN f.name_folded LIKE :plainQuery || '%' THEN 1 ELSE 2 END,
            bm25(food_search, 5.0, 1.0, 3.0, 0.0),
            f.quality_score DESC
        LIMIT :limit
        """
    )
    suspend fun searchBranded(
        ftsQuery: String,
        plainQuery: String,
        limit: Int,
    ): List<FoodWithDisplayName>

    /**
     * Rows to borrow when the reader's table has none (Switzerland has no cucumber), only from
     * tables written in a language the reader reads; [TableLanguages] decides which. [sourceList]
     * is delimited on both ends (`,bls,swiss,`) so one source name cannot match inside another.
     */
    @SkipQueryVerification
    @Query(
        GENERIC_SEARCH_HEAD +
            "INSTR(:sourceList, ',' || f.source || ',') > 0" +
            GENERIC_SEARCH_ORDER
    )
    suspend fun searchBorrowedGeneric(
        ftsQuery: String,
        plainQuery: String,
        languages: List<String>,
        languagePriority: String,
        sourceList: String,
        head: String,
        leadWordSource: String,
        limit: Int,
    ): List<RankedFood>

    /**
     * Every generic source and the languages it serves, for [TableLanguages].
     *
     * The correlated subquery must read `t.source`: a bare `source` resolves to `g.source` and
     * compares each row with itself.
     */
    @Query(
        """
        SELECT t.source, t.lang FROM (
            SELECT f.source AS source, f.lang AS lang, 2 AS own, COUNT(*) AS rows_covered
              FROM foods f
             WHERE f.source <> 'off' AND f.lang IS NOT NULL AND f.lang <> ''
             GROUP BY f.source, f.lang
            UNION ALL
            SELECT f.source AS source, fa.lang AS lang, 1 AS own,
                   COUNT(DISTINCT fa.food_id) AS rows_covered
              FROM food_aliases fa
              JOIN foods f ON f.id = fa.food_id
             WHERE f.source <> 'off' AND INSTR(fa.lang, '-') = 0
             GROUP BY f.source, fa.lang
        ) AS t
        WHERE t.rows_covered * 100 >= :minCoveragePercent *
              (SELECT COUNT(*) FROM foods g WHERE g.source = t.source)
        GROUP BY t.source, t.lang
        ORDER BY t.source, MAX(t.own) DESC, MAX(t.rows_covered) DESC, t.lang
        """
    )
    suspend fun languagesBySource(minCoveragePercent: Int): List<SourceLanguage>

    /**
     * Resolves a scanned code through `food_barcodes`, never `foods.barcode`. Every code maps to its
     * own package record, including ones grouped out of typed search.
     */
    @Query(
        """SELECT f.*, """ + DISPLAY_NAME + """ FROM foods f
        JOIN food_barcodes b ON b.food_id = f.id
        WHERE b.barcode = :barcode LIMIT 1
        """
    )
    suspend fun findByBarcode(
        barcode: String,
        languages: List<String>,
        languagePriority: String,
    ): FoodWithDisplayName?

    @Query(
        """SELECT f.*, """ + DISPLAY_NAME + """ FROM foods f
        WHERE f.id = :id
        """
    )
    suspend fun getById(
        id: Long,
        languages: List<String>,
        languagePriority: String,
    ): FoodWithDisplayName?

    @Query("SELECT * FROM food_servings WHERE food_id = :foodId ORDER BY sort_order")
    suspend fun servingsFor(foodId: Long): List<FoodServingEntity>

    /**
     * The languages one national table serves, its own name language first. An alias language
     * counts only above the coverage threshold (CNF `fr`, Swiss `it` and `fr`, Fineli `sv`).
     * Regional variants such as `en-GB` are excluded.
     */
    @Query(
        """
        SELECT lang FROM (
            SELECT f.lang AS lang, 2 AS own, COUNT(*) AS rows_covered
              FROM foods f
             WHERE f.source = :source AND f.lang IS NOT NULL AND f.lang <> ''
             GROUP BY f.lang
            UNION ALL
            SELECT fa.lang AS lang, 1 AS own, COUNT(DISTINCT fa.food_id) AS rows_covered
              FROM food_aliases fa
              JOIN foods f ON f.id = fa.food_id
             WHERE f.source = :source AND INSTR(fa.lang, '-') = 0
             GROUP BY fa.lang
        )
        WHERE rows_covered * 100 >= :minCoveragePercent *
              (SELECT COUNT(*) FROM foods WHERE source = :source)
        GROUP BY lang
        ORDER BY MAX(own) DESC, MAX(rows_covered) DESC, lang
        """
    )
    suspend fun languagesServedBy(source: String, minCoveragePercent: Int): List<String>

    /** Tells an empty database apart from a search that found nothing. */
    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int

    /** What this database file is: which build, from which sources, and how many foods. */
    @Query("SELECT * FROM database_meta WHERE id = 1")
    suspend fun meta(): DatabaseMetaEntity?
}
