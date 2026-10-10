package com.lethio.macros.domain.repository

import com.lethio.macros.domain.model.DayLog
import com.lethio.macros.domain.model.DiaryHistoryDay
import com.lethio.macros.domain.model.DiaryHistoryWindow
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.Profile
import com.lethio.macros.domain.model.WeightEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/*
 * Repository contracts. Interfaces in the domain layer, so ViewModels and nutrition logic are tested
 * on the JVM with hand-written fakes. They return domain models, never Room entities.
 */

interface FoodRepository {
    suspend fun search(query: String, limit: Int = 30): List<Food>
    suspend fun findByBarcode(barcode: String): Food?
    suspend fun getByRef(ref: FoodRef): Food?
    suspend fun saveCustomFood(food: Food): FoodRef
    suspend fun deleteCustomFood(ref: FoodRef.Custom)


    /**
     * The generic database search is restricted to, or null when it covers every generic source
     * (a country without a national table).
     */
    suspend fun activeDatabase(): ActiveDatabase?
}

interface LogRepository {
    fun observeDay(date: LocalDate): Flow<DayLog>
    fun observeLoggedDates(): Flow<List<LocalDate>>
    suspend fun getEntry(id: Long): LogEntry?
    suspend fun addEntry(entry: LogEntry): Long
    suspend fun updateEntry(entry: LogEntry)
    suspend fun deleteEntry(id: Long)
    /** Atomically capture and remove this date's entries; the returned snapshots support Undo. */
    suspend fun clearDay(date: LocalDate): List<LogEntry>
    /** Restore the complete batch atomically, refusing to overwrite any existing entry. */
    suspend fun restoreEntries(entries: List<LogEntry>)

    /** Re-inserts a deleted entry with its original id, for undo. */
    suspend fun restoreEntry(entry: LogEntry)

    /** Most recently logged distinct foods, newest first. Drives the recents shortcut. */
    suspend fun recentFoods(limit: Int = 30): List<LogEntry>

    /** Every entry, oldest first. Used by export. */
    suspend fun allEntries(): List<LogEntry>

    fun observeFavorites(): Flow<List<LogEntry>>
    fun observeIsFavorite(ref: FoodRef): Flow<Boolean>
    suspend fun toggleFavorite(entry: LogEntry)
}

interface DiaryHistoryRepository {
    /** Exactly 7/28 calendar rows in ascending order; bounded reads, raw snapshot precision. */
    fun observeWindow(window: DiaryHistoryWindow): Flow<List<DiaryHistoryDay>>
}

interface GoalsRepository {
    /** The goals in force on [date] — the latest set whose effective date is not after it. */
    fun observeGoalsFor(date: LocalDate): Flow<Goals>
    suspend fun goalsFor(date: LocalDate): Goals
    suspend fun setGoals(goals: Goals)
    /** Correct an existing saved change only if it and its range still match the shown snapshot. */
    suspend fun replaceSavedGoals(expected: Goals, nextDate: LocalDate?, targets: com.lethio.macros.domain.model.Macros)
    suspend fun deleteSavedGoals(expected: Goals, nextDate: LocalDate?)
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

/** The database search is restricted to, and the languages it is written in, read from the table. */
data class ActiveDatabase(val source: String, val languages: List<String>)
