package com.lethio.macros.data.user

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.Goals
import com.lethio.macros.domain.model.Macros
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalsDaoTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val databaseName = "goals-dao-test.db"
    private val today = LocalDate.of(2026, 9, 27)
    private val clock = MutableTimeProvider()

    private lateinit var database: UserDatabase
    private lateinit var repository: GoalsRepositoryImpl

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() {
        database =
            Room.databaseBuilder(context, UserDatabase::class.java, databaseName)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*UserDatabase.ALL_MIGRATIONS)
                .build()
        repository = GoalsRepositoryImpl(database.goalsDao(), clock)
    }

    private fun goals(date: LocalDate, calories: Double) =
        Goals(
            effectiveFrom = date,
            targets = Macros(calories, 150.0, 65.0, 250.0),
            source = Goals.Source.MANUAL,
        )

    @Test
    fun secondSaveOnSameDatePersistsAfterReopenAndKeepsOneRow() = runTest {
        repository.setGoals(goals(today, 2_000.0))
        val first = database.goalsDao().onDate(today.toString())!!

        clock.instant = Instant.parse("2026-09-27T12:00:00Z")
        repository.setGoals(goals(today, 2_200.0))

        database.close()
        openDatabase()

        assertThat(repository.goalsFor(today).targets.calories).isEqualTo(2_200.0)
        assertThat(database.goalsDao().observeHistory().first()).hasSize(1)
        val saved = database.goalsDao().onDate(today.toString())!!
        assertThat(saved.id).isEqualTo(first.id)
        assertThat(saved.createdAt).isEqualTo(first.createdAt)
    }

    @Test
    fun laterDateKeepsEarlierGoalsAndEffectiveDateLookup() = runTest {
        val tomorrow = today.plusDays(1)
        repository.setGoals(goals(today, 2_000.0))
        repository.setGoals(goals(tomorrow, 2_100.0))

        database.close()
        openDatabase()

        assertThat(repository.goalsFor(today).targets.calories).isEqualTo(2_000.0)
        assertThat(repository.goalsFor(tomorrow).targets.calories).isEqualTo(2_100.0)
        assertThat(repository.goalsFor(tomorrow.plusDays(1)).targets.calories).isEqualTo(2_100.0)
        assertThat(database.goalsDao().observeHistory().first()).hasSize(2)
    }

    private class MutableTimeProvider : TimeProvider {
        var instant: Instant = Instant.parse("2026-09-27T08:00:00Z")

        override fun today(): LocalDate = LocalDate.of(2026, 9, 27)

        override fun now(): Instant = instant

        override fun currentTime(): LocalTime = LocalTime.NOON
    }
}
