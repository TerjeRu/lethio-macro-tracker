package com.lethio.macros.data.user

import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.diary.correctedEntry
import java.time.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class DiaryHistoryPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "diary-history-test.db"
    private val today = LocalDate.of(2026, 10, 4)
    private val clock =
        object : TimeProvider {
            override fun today() = today

            override fun now() = Instant.parse("2026-10-04T12:00:00Z")

            override fun currentTime() = LocalTime.NOON
        }
    private lateinit var db: UserDatabase
    private lateinit var logs: LogRepositoryImpl
    private lateinit var goals: GoalsRepositoryImpl
    private lateinit var history: DiaryHistoryRepositoryImpl
    private val window = DiaryHistoryWindow(today, DiaryHistoryPeriod.SEVEN_DAYS)

    private fun open() {
        db =
            Room.databaseBuilder(context, UserDatabase::class.java, name)
                .setDriver(BundledSQLiteDriver())
                .build()
        logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
        goals = GoalsRepositoryImpl(db.goalsDao(), clock)
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

    @Test
    fun emptyHistoryHasCalendarGapsAndNoInventedSavedTargets() = runBlocking {
        val rows = history.observeWindow(window).first()
        assertThat(rows.map { it.date })
            .containsExactly(
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 3),
                today,
            )
            .inOrder()
        assertThat(
                rows.all { it.entryCount == 0 && it.loggedIntake == null && it.savedGoal == null }
            )
            .isTrue()
        // The ordinary diary's default is real behaviour, but not evidence of a saved history goal.
        assertThat(goals.goalsFor(today).targets.calories).isEqualTo(2000.0)
    }

    @Test
    fun sparseHistoryUsesSnapshotsAndReactsToCorrectionGoalChangeDeleteAndUndo() = runBlocking {
        val first = logs.addEntry(entry(window.start, Macros(150.25, 5.0, 3.0, 27.0)))
        logs.addEntry(entry(window.start, Macros(49.75, 2.0, 1.0, 10.0)))
        val zero = logs.addEntry(entry(LocalDate.of(2026, 9, 30), Macros.ZERO))
        logs.addEntry(entry(today, Macros(620.0, 30.0, 20.0, 80.0)))
        logs.addEntry(entry(window.start.minusDays(1), Macros(9999.0, 1.0, 1.0, 1.0)))
        logs.addEntry(entry(today.plusDays(1), Macros(9999.0, 1.0, 1.0, 1.0)))
        val oldGoal =
            Goals(
                LocalDate.of(2026, 9, 29),
                Macros(1800.0, 100.0, 60.0, 220.0),
                Goals.Source.MANUAL,
            )
        val newGoal =
            Goals(
                LocalDate.of(2026, 10, 2),
                Macros(2200.0, 140.0, 70.0, 250.0),
                Goals.Source.CALCULATED,
            )
        goals.setGoals(oldGoal)
        goals.setGoals(newGoal)
        goals.setGoals(
            newGoal.copy(effectiveFrom = today.plusDays(1), targets = Macros(9999.0, 1.0, 1.0, 1.0))
        )
        val updates = Channel<List<DiaryHistoryDay>>(Channel.UNLIMITED)
        val job = launch { history.observeWindow(window).collect { updates.send(it) } }
        suspend fun next(): List<DiaryHistoryDay> = withTimeout(5_000) { updates.receive() }
        try {
            val initial = next()
            assertThat(initial[0].entryCount).isEqualTo(2)
            assertThat(initial[0].loggedIntake).isEqualTo(Macros(200.0, 7.0, 4.0, 37.0))
            assertThat(initial[0].savedGoal).isNull()
            assertThat(initial[1].loggedIntake).isNull()
            assertThat(initial[1].savedGoal).isEqualTo(oldGoal)
            assertThat(initial[2].entryCount).isEqualTo(1)
            assertThat(initial[2].loggedIntake).isEqualTo(Macros.ZERO)
            assertThat(initial[3].savedGoal).isEqualTo(oldGoal)
            assertThat(initial[4].savedGoal).isEqualTo(newGoal)
            assertThat(initial.last().loggedIntake!!.calories).isEqualTo(620.0)
            val edited = correctedEntry(logs.getEntry(first)!!, "60", MealType.DINNER)!!
            logs.updateEntry(edited)
            val changed = next()
            assertThat(changed[0].entryCount).isEqualTo(2)
            assertThat(changed[0].loggedIntake).isEqualTo(Macros(275.125, 9.5, 5.5, 50.5))
            val changedGoal = newGoal.copy(targets = Macros(2300.0, 140.0, 70.0, 250.0))
            goals.setGoals(changedGoal)
            val goalUpdate = next()
            assertThat(goalUpdate[3].savedGoal).isEqualTo(oldGoal)
            assertThat(goalUpdate[4].savedGoal).isEqualTo(changedGoal)
            logs.deleteEntry(zero)
            val missing = next()
            assertThat(missing[2].entryCount).isEqualTo(0)
            assertThat(missing[2].loggedIntake).isNull()
            logs.deleteEntry(first)
            assertThat(next()[0].loggedIntake!!.calories).isEqualTo(49.75)
            logs.restoreEntry(edited)
            assertThat(next()[0].loggedIntake!!.calories).isEqualTo(275.125)
        } finally {
            job.cancel()
            job.join()
            updates.close()
        }
        db.close()
        open()
        assertThat(history.observeWindow(window).first()[0].loggedIntake!!.calories)
            .isEqualTo(275.125)
    }

    @Test
    fun savedBaselineBeforeWindowIsUsedWithoutReadingAllGoalHistory() = runBlocking {
        val baseline =
            Goals(LocalDate.of(2025, 1, 1), Macros(1900.0, 110.0, 55.0, 220.0), Goals.Source.MANUAL)
        goals.setGoals(baseline.copy(effectiveFrom = LocalDate.of(2024, 1, 1)))
        goals.setGoals(baseline)
        val changed =
            baseline.copy(effectiveFrom = today, targets = Macros(2100.0, 120.0, 65.0, 240.0))
        goals.setGoals(changed)
        val raw =
            db.goalsDao().observeHistoryInRange(window.start.toString(), today.toString()).first()
        assertThat(raw).hasSize(2)
        val rows = history.observeWindow(window).first()
        assertThat(rows.first().savedGoal).isEqualTo(baseline)
        assertThat(rows.last().savedGoal).isEqualTo(changed)
    }

    @Test
    fun multiYearDenseHistoryKeepsReadsBoundedAndUsesExistingIndexes(): Unit = runBlocking {
        logs.allEntries() // Create the real exported Room schema before direct synthetic bulk
        // seeding.
        db.close()
        val plans = mutableListOf<String>()
        BundledSQLiteDriver().open(context.getDatabasePath(name).absolutePath).use { conn ->
            conn.prepare("BEGIN").use { it.step() }
            conn
                .prepare(
                    """INSERT INTO daily_logs(log_date,meal_type,food_ref_kind,food_ref_id,
                food_name,brand,quantity,unit_label,grams,calories,protein_g,fat_g,carbs_g,created_at,updated_at)
                VALUES (?,1,'quick',NULL,'Synthetic history',NULL,40,'g',40,100,10,2,30,0,0)"""
                )
                .use { insert ->
                    val earliest = today.minusDays(3999)
                    repeat(4000) { day ->
                        repeat(10) {
                            insert.bindText(1, earliest.plusDays(day.toLong()).toString())
                            insert.step()
                            insert.reset()
                        }
                    }
                }
            conn
                .prepare(
                    """INSERT INTO goals(effective_from,calories_target,protein_target_g,
                fat_target_g,carbs_target_g,source,created_at) VALUES (?,2000,120,65,250,'manual',0)"""
                )
                .use { insert ->
                    repeat(134) { index ->
                        insert.bindText(1, today.minusDays(3999).plusDays(index * 30L).toString())
                        insert.step()
                        insert.reset()
                    }
                }
            conn.prepare("COMMIT").use { it.step() }
            for (query in listOf(DIARY_HISTORY_ENTRIES_SQL, DIARY_HISTORY_GOALS_SQL)) {
                conn.prepare("EXPLAIN QUERY PLAN $query").use { explain ->
                    explain.bindText(1, today.minusDays(27).toString())
                    explain.bindText(2, today.toString())
                    while (explain.step()) plans += explain.getText(3)
                }
            }
        }
        open()
        val dense = DiaryHistoryWindow(today, DiaryHistoryPeriod.TWENTY_EIGHT_DAYS)
        val start = System.nanoTime()
        val rows = history.observeWindow(dense).first()
        val coldMs = (System.nanoTime() - start) / 1_000_000.0
        assertThat(rows).hasSize(28)
        assertThat(
                rows.all {
                    it.entryCount == 10 && it.loggedIntake == Macros(1000.0, 100.0, 20.0, 300.0)
                }
            )
            .isTrue()
        assertThat(
                db.dailyLogDao()
                    .observeEntriesInRange(dense.start.toString(), today.toString())
                    .first()
            )
            .hasSize(280)
        assertThat(
                db.goalsDao()
                    .observeHistoryInRange(dense.start.toString(), today.toString())
                    .first()
            )
            .hasSize(2)
        assertThat(plans.any { it.contains("SEARCH daily_logs USING INDEX") }).isTrue()
        assertThat(plans.any { it.contains("INDEX index_goals_effective_from") }).isTrue()
        assertThat(plans.none { it.contains("SCAN daily_logs") || it.contains("SCAN goals") })
            .isTrue()
        val warmMs =
            List(20) {
                    val mark = System.nanoTime()
                    assertThat(history.observeWindow(dense).first()).isEqualTo(rows)
                    (System.nanoTime() - mark) / 1_000_000.0
                }
                .sorted()
        Log.i(
            "DiaryHistoryMeasurement",
            "40000 logs/4000 days/134 goals; read 280 snapshots/2 goals, returned 28 totals; " +
                "coldMs=$coldMs warmMedianMs=${warmMs[10]} warmMaxMs=${warmMs.last()} plans=$plans",
        )
    }

    private fun entry(date: LocalDate, macros: Macros) =
        LogEntry(
            0,
            date,
            MealType.BREAKFAST,
            FoodRef.QuickAdd,
            "Synthetic snapshot",
            quantity = Quantity.ofGrams(40.0),
            macros = macros,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
