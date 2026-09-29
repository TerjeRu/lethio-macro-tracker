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

@Dao
interface DailyLogDao {

    @Query("SELECT * FROM daily_logs WHERE log_date = :date ORDER BY meal_type, created_at")
    fun observeForDate(date: String): Flow<List<DailyLogEntity>>

    @Query("SELECT DISTINCT log_date FROM daily_logs ORDER BY log_date DESC")
    fun observeLoggedDates(): Flow<List<String>>

    @Query("SELECT * FROM daily_logs WHERE id = :id")
    suspend fun getById(id: Long): DailyLogEntity?

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(entry: DailyLogEntity)

    @Update
    suspend fun update(entry: DailyLogEntity)

    @Query("DELETE FROM daily_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface GoalsDao {

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

    @Query("SELECT * FROM weight_entries WHERE measured_on = :date")
    suspend fun findByDate(date: String): WeightEntryEntity?

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

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE food_ref_kind = :kind AND food_ref_id = :id)"
    )
    suspend fun isFavorite(kind: String, id: Long): Boolean

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
