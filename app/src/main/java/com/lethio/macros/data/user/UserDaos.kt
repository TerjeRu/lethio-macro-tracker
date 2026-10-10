package com.lethio.macros.data.user

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lethio.macros.data.user.entity.CustomFoodEntity
import com.lethio.macros.data.user.entity.CustomFoodServingEntity
import com.lethio.macros.data.user.entity.DailyLogEntity
import com.lethio.macros.data.user.entity.FavoriteEntity
import com.lethio.macros.data.user.entity.GoalsEntity
import com.lethio.macros.data.user.entity.ProfileEntity
import com.lethio.macros.data.user.entity.WeightEntryEntity
import kotlinx.coroutines.flow.Flow

internal const val DIARY_HISTORY_TOTALS_SQL = """
    SELECT log_date AS date, COUNT(*) AS entryCount,
           SUM(calories) AS calories, SUM(protein_g) AS proteinG,
           SUM(fat_g) AS fatG, SUM(carbs_g) AS carbsG
    FROM daily_logs
    WHERE log_date >= :start AND log_date <= :end
    GROUP BY log_date ORDER BY log_date
"""

internal const val DIARY_HISTORY_ENTRIES_SQL = """
    SELECT * FROM daily_logs WHERE log_date >= :start AND log_date <= :end ORDER BY log_date, id
"""

internal const val DIARY_HISTORY_GOALS_SQL = """
    SELECT * FROM goals
    WHERE (effective_from > :start AND effective_from <= :end)
       OR id = (SELECT id FROM goals WHERE effective_from <= :start
                ORDER BY effective_from DESC LIMIT 1)
    ORDER BY effective_from
"""

data class LoggedDayTotals(
    val date: String, val entryCount: Int, val calories: Double,
    val proteinG: Double, val fatG: Double, val carbsG: Double,
)

@Dao
interface DailyLogDao {

    /** Bounded history window; retain qualifications while summing real saved snapshots. */
    @Query(DIARY_HISTORY_ENTRIES_SQL)
    fun observeEntriesInRange(start: String, end: String): Flow<List<DailyLogEntity>>

    @Query(DIARY_HISTORY_TOTALS_SQL)
    fun observeTotalsInRange(start: String, end: String): Flow<List<LoggedDayTotals>>

    @Query("SELECT * FROM daily_logs WHERE log_date = :date ORDER BY meal_type, created_at")
    fun observeForDate(date: String): Flow<List<DailyLogEntity>>

    @Query("SELECT DISTINCT log_date FROM daily_logs ORDER BY log_date DESC")
    fun observeLoggedDates(): Flow<List<String>>

    @Query("SELECT * FROM daily_logs WHERE id = :id")
    suspend fun getById(id: Long): DailyLogEntity?

    /**
     * Distinct recently logged foods, most recent first. Relies on SQLite's bare-column rule: with a
     * single `MAX()` in the select list, the other columns come from the row holding the maximum.
     */
    @Query(
        """
        SELECT * FROM daily_logs
        WHERE id IN (
            SELECT id FROM (
                SELECT id, MAX(created_at)
                FROM daily_logs
                GROUP BY food_ref_kind, food_ref_id, food_name
            )
        )
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentFoods(limit: Int): List<DailyLogEntity>

    @Query("SELECT * FROM daily_logs ORDER BY log_date, meal_type, created_at")
    suspend fun getAllForExport(): List<DailyLogEntity>

    @Insert
    suspend fun insert(entry: DailyLogEntity): Long

    /** Re-inserts with an explicit id so undo restores the original row, not a copy. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(entry: DailyLogEntity)

    @Update
    suspend fun update(entry: DailyLogEntity): Int

    @Query("DELETE FROM daily_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM daily_logs WHERE log_date = :date ORDER BY id")
    suspend fun entriesOn(date: String): List<DailyLogEntity>

    @Query("DELETE FROM daily_logs WHERE log_date = :date")
    suspend fun deleteDate(date: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(entries: List<DailyLogEntity>)

    @Transaction
    suspend fun clearDate(date: String): List<DailyLogEntity> {
        val entries = entriesOn(date)
        deleteDate(date)
        return entries
    }

    @Transaction
    suspend fun restoreBatch(entries: List<DailyLogEntity>) { insertBatch(entries) }
}

@Dao
interface GoalsDao {

    /** The baseline effective at the first date, plus changes within the requested window. */
    @Query(DIARY_HISTORY_GOALS_SQL)
    fun observeHistoryInRange(start: String, end: String): Flow<List<GoalsEntity>>

    /**
     * The goals in force on [date]: the most recent set whose effective date is not after it.
     * Returns null when no saved goal was yet effective on the requested date, including dates
     * before the first saved goal.
     */
    @Query(
        """
        SELECT * FROM goals
        WHERE effective_from <= :date
        ORDER BY effective_from DESC
        LIMIT 1
        """
    )
    fun observeEffectiveOn(date: String): Flow<GoalsEntity?>

    @Query(
        """
        SELECT * FROM goals
        WHERE effective_from <= :date
        ORDER BY effective_from DESC
        LIMIT 1
        """
    )
    suspend fun effectiveOn(date: String): GoalsEntity?

    @Query("SELECT * FROM goals ORDER BY effective_from DESC")
    fun observeHistory(): Flow<List<GoalsEntity>>

    @Query("SELECT * FROM goals WHERE effective_from = :date LIMIT 1")
    suspend fun onDate(date: String): GoalsEntity?

    @Upsert
    suspend fun upsert(goals: GoalsEntity)

    @Transaction
    suspend fun saveForDate(goals: GoalsEntity) {
        val existing = onDate(goals.effectiveFrom)
        upsert(existing?.let { goals.copy(id = it.id, createdAt = it.createdAt) } ?: goals)
    }

    @Query("SELECT effective_from FROM goals WHERE effective_from > :date ORDER BY effective_from LIMIT 1")
    suspend fun nextDate(date: String): String?

    @Update
    suspend fun updateExisting(goals: GoalsEntity): Int

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteId(id: Long): Int

    /** Ignore persistence metadata in the caller snapshot; preserve it on a correction. */
    private fun sameValues(a: GoalsEntity, b: GoalsEntity): Boolean =
        a.copy(id = 0, createdAt = 0) == b.copy(id = 0, createdAt = 0)

    @Transaction
    suspend fun replaceSaved(expected: GoalsEntity, next: String?, replacement: GoalsEntity) {
        val existing = onDate(expected.effectiveFrom)
        check(existing != null && sameValues(existing, expected) && nextDate(expected.effectiveFrom) == next) {
            "Saved goals or their date range changed"
        }
        check(replacement.effectiveFrom == expected.effectiveFrom)
        check(updateExisting(replacement.copy(id = existing.id, createdAt = existing.createdAt)) == 1)
    }

    @Transaction
    suspend fun deleteSaved(expected: GoalsEntity, next: String?) {
        val existing = onDate(expected.effectiveFrom)
        check(existing != null && sameValues(existing, expected) && nextDate(expected.effectiveFrom) == next) {
            "Saved goals or their date range changed"
        }
        check(deleteId(existing.id) == 1)
    }
}

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile WHERE id = :id")
    fun observeProfile(id: Int = ProfileEntity.SINGLETON_ID): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun getProfile(id: Int = ProfileEntity.SINGLETON_ID): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface WeightDao {

    @Query("SELECT * FROM weight_entries ORDER BY measured_on DESC")
    fun observeAll(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries ORDER BY measured_on DESC LIMIT 1")
    suspend fun latest(): WeightEntryEntity?

    /** The row for a given day, if there is one. See [WeightRepositoryImpl.upsert]. */
    @Query("SELECT * FROM weight_entries WHERE measured_on = :date")
    suspend fun findByDate(date: String): WeightEntryEntity?

    /**
     * Callers must pass the existing row's id for that date; use [WeightRepositoryImpl.upsert]. Room's
     * `@Upsert` falls back to an UPDATE on the primary key, but the conflict here is on the unique
     * `measured_on` index, so a new entry would update nothing and report success.
     */
    @Upsert
    suspend fun upsert(entry: WeightEntryEntity)

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY sort_order, created_at DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE food_ref_kind = :kind AND food_ref_id = :id)"
    )
    fun observeIsFavorite(kind: String, id: Long): Flow<Boolean>

    /** One-shot read, so a toggle never acts on a stale flow value. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE food_ref_kind = :kind AND food_ref_id = :id)"
    )
    suspend fun isFavorite(kind: String, id: Long): Boolean

    /** Unique index on (kind, id) makes a duplicate insert a no-op rather than a second row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE food_ref_kind = :kind AND food_ref_id = :id")
    suspend fun delete(kind: String, id: Long)
}

@Dao
interface CustomFoodDao {

    @Query("SELECT * FROM custom_foods WHERE id = :id")
    suspend fun getById(id: Long): CustomFoodEntity?

    @Query("SELECT * FROM custom_foods WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): CustomFoodEntity?

    /**
     * The custom-food half of search. [query] must already be folded by `foldForSearch` and escaped
     * for `LIKE`, as `FoodRepositoryImpl.search` does. Ordered by the real name, never the folded one.
     */
    @Query(
        """
        SELECT * FROM custom_foods
        WHERE name_folded LIKE '%' || :query || '%' ESCAPE '\'
           OR brand_folded LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY name
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<CustomFoodEntity>

    @Query("SELECT * FROM custom_food_servings WHERE food_id = :foodId ORDER BY sort_order")
    suspend fun servingsFor(foodId: Long): List<CustomFoodServingEntity>

    @Query("SELECT * FROM custom_foods ORDER BY name")
    suspend fun getAllForExport(): List<CustomFoodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(food: CustomFoodEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServings(servings: List<CustomFoodServingEntity>)

    @Query("DELETE FROM custom_food_servings WHERE food_id = :foodId")
    suspend fun deleteServingsFor(foodId: Long)

    @Delete
    suspend fun delete(food: CustomFoodEntity)

    @Transaction
    suspend fun upsertWithServings(
        food: CustomFoodEntity,
        servings: List<CustomFoodServingEntity>,
    ): Long {
        val id = insert(food)
        deleteServingsFor(id)
        if (servings.isNotEmpty()) {
            insertServings(servings.map { it.copy(id = 0, foodId = id) })
        }
        return id
    }
}
