package com.lethio.macros.data.user

import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toDomain
import com.lethio.macros.data.toEntity
import com.lethio.macros.data.user.entity.FavoriteEntity
import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.model.Quantity
import com.lethio.macros.domain.repository.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepositoryImpl @Inject constructor(
    private val dailyLogDao: DailyLogDao,
    private val favoriteDao: FavoriteDao,
    private val timeProvider: TimeProvider,
) : LogRepository {

    override fun observeDay(date: LocalDate): Flow<DayLog> =
        dailyLogDao.observeForDate(date.toString()).map { rows ->
            val entries = rows.map { it.toDomain() }
            DayLog(
                date = date,
                entriesByMeal = entries.groupBy { it.meal },

                totals = entries.fold(Macros.ZERO) { acc, entry -> acc + entry.macros },
            )
        }

    override fun observeLoggedDates(): Flow<List<LocalDate>> =
        dailyLogDao.observeLoggedDates().map { dates ->
            dates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        }

    override suspend fun getEntry(id: Long): LogEntry? = dailyLogDao.getById(id)?.toDomain()

    override suspend fun addEntry(entry: LogEntry): Long {
        val now = timeProvider.now()
        return dailyLogDao.insert(
            entry.copy(id = LogEntry.NEW, createdAt = now, updatedAt = now).toEntity(),
        )
    }

    override suspend fun updateEntry(entry: LogEntry) {
        dailyLogDao.update(entry.copy(updatedAt = timeProvider.now()).toEntity())
    }

    override suspend fun deleteEntry(id: Long) = dailyLogDao.deleteById(id)

    override suspend fun restoreEntry(entry: LogEntry) = dailyLogDao.insertWithId(entry.toEntity())

    override suspend fun recentFoods(limit: Int): List<LogEntry> =
        dailyLogDao.recentFoods(limit).map { it.toDomain() }

    override suspend fun allEntries(): List<LogEntry> =
        dailyLogDao.getAllForExport().map { it.toDomain() }

    override fun observeFavorites(): Flow<List<LogEntry>> =
        favoriteDao.observeAll().map { favorites ->
            favorites.map { favorite ->
                val created = Instant.ofEpochMilli(favorite.createdAt)

                LogEntry(
                    id = favorite.id,
                    date = timeProvider.today(),
                    meal = MealType.SNACK,
                    foodRef = FoodRef.of(
                        FoodRef.Kind.fromValue(favorite.foodRefKind),
                        favorite.foodRefId,
                    ),
                    foodName = favorite.foodName,
                    brand = favorite.brand,
                    quantity = Quantity.ofGrams(0.0),
                    macros = Macros.ZERO,
                    createdAt = created,
                    updatedAt = created,
                )
            }
        }

    override fun observeIsFavorite(ref: FoodRef): Flow<Boolean> {
        val id = ref.id ?: return flowOf(false)
        return favoriteDao.observeIsFavorite(ref.kind.value, id)
    }

    override suspend fun toggleFavorite(entry: LogEntry) {
        val id = entry.foodRef.id ?: return
        val kind = entry.foodRef.kind.value
        if (favoriteDao.isFavorite(kind, id)) {
            favoriteDao.delete(kind, id)
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    foodRefKind = kind,
                    foodRefId = id,
                    foodName = entry.foodName,
                    brand = entry.brand,
                    createdAt = timeProvider.now().toEpochMilli(),
                ),
            )
        }
    }
}
