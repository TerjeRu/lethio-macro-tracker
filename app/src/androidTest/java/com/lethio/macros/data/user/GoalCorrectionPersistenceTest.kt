package com.lethio.macros.data.user

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toEntity
import com.lethio.macros.domain.model.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class GoalCorrectionPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "goal-correction-test.db"
    private val date = LocalDate.of(2026, 9, 30)
    private val clock =
        object : TimeProvider {
            override fun today() = LocalDate.of(2026, 10, 5)

            override fun now() = Instant.parse("2026-10-05T12:00:00Z")

            override fun currentTime() = LocalTime.NOON
        }
    private lateinit var db: UserDatabase
    private lateinit var goals: GoalsRepositoryImpl
    private lateinit var logs: LogRepositoryImpl
    private lateinit var history: DiaryHistoryRepositoryImpl
    private val earlier =
        Goals(date.minusDays(3), Macros(1800.0, 100.0, 60.0, 220.0), Goals.Source.MANUAL)
    private val bad = Goals(date, Macros(9_000_000.0, 150.0, 65.0, 250.0), Goals.Source.CALCULATED)
    private val later =
        Goals(date.plusDays(3), Macros(2300.0, 140.0, 70.0, 250.0), Goals.Source.MANUAL)

    private fun open() {
        db =
            Room.databaseBuilder(context, UserDatabase::class.java, name)
                .setDriver(BundledSQLiteDriver())
                .build()
        goals = GoalsRepositoryImpl(db.goalsDao(), clock)
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
        history = DiaryHistoryRepositoryImpl(db.dailyLogDao(), db.goalsDao())
    }

    @Before
    fun setup() {
        context.deleteDatabase(name)
        open()
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun seed() {
        goals.setGoals(earlier)
        // Legacy invalid data must be repairable; seed it through the DAO, outside new writer
        // validation.
        db.goalsDao().saveForDate(bad.toEntity(Instant.parse("2026-09-30T10:00:00Z")))
        goals.setGoals(later)
    }

    private fun entry(day: LocalDate) =
        LogEntry(
            id = LogEntry.NEW,
            date = day,
            meal = MealType.LUNCH,
            foodRef = FoodRef.QuickAdd,
            foodName = "Synthetic lunch",
            quantity = Quantity.ofGrams(100.0),
            macros = Macros(334.0, 9.0, 9.0, 54.0),
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    private suspend fun refuses(action: suspend () -> Unit) {
        var refused = false
        try {
            action()
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertThat(refused).isTrue()
    }

    @Test
    fun correctingBadGoalRepairsWholeRangePreservesIdentityFoodAndLaterGoalsAfterReopen() =
        runBlocking {
            seed()
            val id = logs.addEntry(entry(date))
            val original = logs.getEntry(id)
            val before = db.goalsDao().onDate(date.toString())!!
            goals.replaceSavedGoals(bad, later.effectiveFrom, Macros(2000.0, 150.0, 65.0, 250.0))
            val corrected = db.goalsDao().onDate(date.toString())!!
            assertThat(corrected.id).isEqualTo(before.id)
            assertThat(corrected.createdAt).isEqualTo(before.createdAt)
            assertThat(goals.goalsFor(date.minusDays(1))).isEqualTo(earlier)
            for (offset in 0..2) assertThat(
                    goals.goalsFor(date.plusDays(offset.toLong())).targets.calories
                )
                .isEqualTo(2000.0)
            assertThat(goals.goalsFor(later.effectiveFrom)).isEqualTo(later)
            assertThat(logs.getEntry(id)).isEqualTo(original)
            val rows =
                history
                    .observeWindow(DiaryHistoryWindow(clock.today(), DiaryHistoryPeriod.SEVEN_DAYS))
                    .first()
            assertThat(rows.first { it.date == date }.savedGoal!!.targets.calories)
                .isEqualTo(2000.0)
            db.close()
            open()
            assertThat(db.goalsDao().onDate(date.toString())).isEqualTo(corrected)
            assertThat(logs.getEntry(id)).isEqualTo(original)
        }

    @Test
    fun removingBadGoalFallsBackOnlyUntilNextChangeAndCanRemoveFirstAndLastRecords() = runBlocking {
        seed()
        goals.deleteSavedGoals(bad, later.effectiveFrom)
        assertThat(goals.goalsFor(date)).isEqualTo(earlier)
        assertThat(goals.goalsFor(date.plusDays(2))).isEqualTo(earlier)
        assertThat(goals.goalsFor(later.effectiveFrom)).isEqualTo(later)
        goals.deleteSavedGoals(earlier, later.effectiveFrom)
        val rows =
            history
                .observeWindow(DiaryHistoryWindow(clock.today(), DiaryHistoryPeriod.SEVEN_DAYS))
                .first()
        assertThat(rows.first { it.date == date }.savedGoal).isNull()
        assertThat(goals.goalsFor(date).targets.calories).isEqualTo(2000.0)
        goals.deleteSavedGoals(later, null)
        assertThat(goals.observeHistory().first()).isEmpty()
        db.close()
        open()
        assertThat(goals.observeHistory().first()).isEmpty()
    }

    @Test
    fun staleOrDeletedGoalAndChangedRangeAreRefusedWithoutResurrection() = runBlocking {
        seed()
        val targets = Macros(2000.0, 150.0, 65.0, 250.0)
        goals.replaceSavedGoals(bad, later.effectiveFrom, targets)
        refuses {
            goals.replaceSavedGoals(bad, later.effectiveFrom, targets.copy(calories = 2100.0))
        }
        refuses { goals.deleteSavedGoals(bad, later.effectiveFrom) }
        val current = bad.copy(targets = targets, source = Goals.Source.MANUAL)
        goals.setGoals(later.copy(effectiveFrom = date.plusDays(1)))
        refuses { goals.deleteSavedGoals(current, later.effectiveFrom) }
        refuses { goals.replaceSavedGoals(current, later.effectiveFrom, targets) }
        goals.deleteSavedGoals(current, date.plusDays(1))
        refuses { goals.replaceSavedGoals(current, date.plusDays(1), targets) }
        assertThat(db.goalsDao().onDate(date.toString())).isNull()
    }

    @Test
    fun everyGoalWriterRejectsInvalidTargetsButDoesNotRestrictLoggedIntake() = runBlocking {
        seed()
        for (m in
            listOf(
                Macros(9_000_000.0, 150.0, 65.0, 250.0),
                Macros(2000.0, 5001.0, 65.0, 250.0),
                Macros(Double.NaN, 150.0, 65.0, 250.0),
                Macros(500.0, 150.0, 65.0, 250.0),
            )) {
            var writesRefused = 0
            try {
                goals.setGoals(bad.copy(targets = m))
            } catch (_: IllegalArgumentException) {
                writesRefused++
            }
            try {
                goals.replaceSavedGoals(bad, later.effectiveFrom, m)
            } catch (_: IllegalArgumentException) {
                writesRefused++
            }
            assertThat(writesRefused).isEqualTo(2)
        }
        assertThat(goals.goalsFor(date)).isEqualTo(bad)
        val id = logs.addEntry(entry(date).copy(macros = Macros(25000.0, 10.0, 10.0, 10.0)))
        assertThat(logs.getEntry(id)!!.macros.calories).isEqualTo(25000.0)
    }

    @Test
    fun clearAndUndoAreDateScopedPreserveSnapshotsGoalsAndNewEntriesAcrossReopen() = runBlocking {
        seed()
        val ids =
            listOf(
                logs.addEntry(entry(date)),
                logs.addEntry(entry(date).copy(meal = MealType.DINNER)),
            )
        val other = logs.addEntry(entry(date.minusDays(1)))
        val original = ids.map { logs.getEntry(it)!! }
        val removed = logs.clearDay(date)
        assertThat(removed).containsExactlyElementsIn(original)
        assertThat(logs.observeDay(date).first().entriesByMeal).isEmpty()
        assertThat(goals.goalsFor(date)).isEqualTo(bad)
        assertThat(logs.getEntry(other)).isNotNull()
        val rows =
            history
                .observeWindow(DiaryHistoryWindow(clock.today(), DiaryHistoryPeriod.SEVEN_DAYS))
                .first()
        assertThat(rows.first { it.date == date }.loggedIntake).isNull()
        val newer = logs.addEntry(entry(date).copy(foodName = "New entry"))
        logs.restoreEntries(removed)
        db.close()
        open()
        assertThat(ids.map { logs.getEntry(it)!! }).containsExactlyElementsIn(original)
        assertThat(logs.getEntry(newer)!!.foodName).isEqualTo("New entry")
        assertThat(logs.getEntry(other)).isNotNull()
    }

    @Test
    fun undoBatchConflictRollsBackWithoutOverwritingOrPartiallyRestoring() = runBlocking {
        val first = logs.addEntry(entry(date))
        val second = logs.addEntry(entry(date))
        val removed = logs.clearDay(date)
        val conflict = removed.single { it.id == second }.copy(foodName = "Concurrent entry")
        logs.restoreEntry(conflict)
        var refused = false
        try {
            logs.restoreEntries(removed)
        } catch (_: Exception) {
            refused = true
        }
        assertThat(refused).isTrue()
        assertThat(logs.getEntry(first)).isNull()
        assertThat(logs.getEntry(second)).isEqualTo(conflict)
    }
}
