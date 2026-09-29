package com.lethio.macros.data.food

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.SkipQueryVerification
import com.lethio.macros.data.food.entity.DatabaseMetaEntity
import com.lethio.macros.data.food.entity.FoodEntity
import com.lethio.macros.data.food.entity.FoodServingEntity

data class FoodWithDisplayName(
    @Embedded val food: FoodEntity,
    @ColumnInfo(name = "display_name") val displayName: String,
)

data class RankedFood(
    @Embedded val result: FoodWithDisplayName,
    @ColumnInfo(name = "match_tier") val matchTier: Int,
)

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
                """ + READER_ALIAS_TIER + """
            )"""

private const val GENERIC_SEARCH_HEAD = """
        SELECT f.*, """ + DISPLAY_NAME + """,
               """ + GENERIC_MATCH_TIER + """ AS match_tier
        FROM food_search s
        JOIN foods f ON f.id = s.rowid
        WHERE food_search MATCH :ftsQuery
          AND f.source <> 'off'
          AND """

private const val HEAD_WORD_ARM = """
            CASE
                WHEN :head = '' THEN 0
                WHEN ' ' || REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' ') || ' '
                     LIKE '% ' || :head || ' %' THEN 0
                ELSE 1
            END"""

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
            COALESCE((SELECT 1 - preferred FROM food_search_members WHERE food_id = f.id), 0),
            """ + HEAD_WORD_ARM + """,
            f.basic_rank,
            LENGTH(f.name) - LENGTH(REPLACE(f.name, ' ', '')),
            LENGTH(f.name),
            bm25(food_search, 5.0, 1.0, 3.0, 0.0),
            f.quality_score DESC
        LIMIT :limit
        """

data class SourceLanguage(val source: String, val lang: String)

@Dao
interface FoodDao {

    @SkipQueryVerification
    @Query(GENERIC_SEARCH_HEAD + "(:nationalSource = '' OR f.source = :nationalSource)" + GENERIC_SEARCH_ORDER)
    suspend fun searchPrimarySource(
        ftsQuery: String,
        plainQuery: String,
        languages: List<String>,
        languagePriority: String,
        nationalSource: String,
        head: String,
        limit: Int,
    ): List<RankedFood>

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

    @SkipQueryVerification
    @Query(GENERIC_SEARCH_HEAD + "f.source <> :nationalSource" + GENERIC_SEARCH_ORDER)
    suspend fun searchFallbackGeneric(
        ftsQuery: String,
        plainQuery: String,
        languages: List<String>,
        languagePriority: String,
        nationalSource: String,
        head: String,
        limit: Int,
    ): List<RankedFood>

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
        limit: Int,
    ): List<RankedFood>

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

    @Query(
        """
        SELECT lang FROM (
            SELECT DISTINCT lang FROM food_aliases
            UNION
            SELECT DISTINCT lang FROM foods WHERE lang IS NOT NULL AND lang <> ''
        )
        ORDER BY lang
        """
    )
    suspend fun availableNameLanguages(): List<String>

    @Query("SELECT * FROM food_servings WHERE food_id = :foodId ORDER BY sort_order")
    suspend fun servingsFor(foodId: Long): List<FoodServingEntity>

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

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int

    @Query("SELECT * FROM database_meta WHERE id = 1")
    suspend fun meta(): DatabaseMetaEntity?
}
