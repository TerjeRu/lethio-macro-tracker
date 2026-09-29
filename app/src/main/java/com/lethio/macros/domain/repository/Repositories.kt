package com.lethio.macros.domain.repository

import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.WeightEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface FoodRepository {
    suspend fun search(query: String, limit: Int = 30): List<Food>
    suspend fun findByBarcode(barcode: String): Food?
    suspend fun getByRef(ref: FoodRef): Food?
    suspend fun saveCustomFood(food: Food): FoodRef
    suspend fun deleteCustomFood(ref: FoodRef.Custom)

    suspend fun availableNameLanguages(): List<String>

    suspend fun activeDatabase(): ActiveDatabase?
}

interface LogRepository {
    fun observeDay(date: LocalDate): Flow<DayLog>
    fun observeLoggedDates(): Flow<List<LocalDate>>
    suspend fun getEntry(id: Long): LogEntry?
    suspend fun addEntry(entry: LogEntry): Long
    suspend fun updateEntry(entry: LogEntry)
    suspend fun deleteEntry(id: Long)

    suspend fun restoreEntry(entry: LogEntry)

    suspend fun recentFoods(limit: Int = 30): List<LogEntry>

    suspend fun allEntries(): List<LogEntry>

    fun observeFavorites(): Flow<List<LogEntry>>
    fun observeIsFavorite(ref: FoodRef): Flow<Boolean>
    suspend fun toggleFavorite(entry: LogEntry)
}

interface GoalsRepository {

    fun observeGoalsFor(date: LocalDate): Flow<Goals>
    suspend fun goalsFor(date: LocalDate): Goals
    suspend fun setGoals(goals: Goals)
    fun observeHistory(): Flow<List<Goals>>
}

interface ProfileRepository {
    fun observeProfile(): Flow<Profile>
    suspend fun getProfile(): Profile
    suspend fun saveProfile(profile: Profile)
}

interface WeightRepository {
    fun observeEntries(): Flow<List<WeightEntry>>
    suspend fun latest(): WeightEntry?
    suspend fun upsert(entry: WeightEntry)
    suspend fun delete(id: Long)
}

data class ActiveDatabase(val source: String, val languages: List<String>)
