package com.lethio.macros.data.user

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.diary.correctedEntry
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DiaryCorrectionPersistenceTest {
    @Test
    fun correctedSnapshotSurvivesReopenAndDeletedEntryIsNotRecreated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "diary-correction-test.db"
        context.deleteDatabase(name)
        fun open() =
            Room.databaseBuilder(context, UserDatabase::class.java, name)
                .setDriver(BundledSQLiteDriver())
                .build()
        val clock =
            object : TimeProvider {
                override fun today() = LocalDate.of(2026, 10, 4)

                override fun now() = Instant.parse("2026-10-04T12:00:00Z")

                override fun currentTime() = LocalTime.NOON
            }
        var db = open()
        try {
            var repo = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
            val original =
                LogEntry(
                    0,
                    LocalDate.of(2026, 9, 1),
                    MealType.BREAKFAST,
                    FoodRef.Bundled(999999),
                    "Historical snapshot",
                    quantity = Quantity(2.0, MeasureUnit.Portion("slice", 20.0), 40.0),
                    macros = Macros(150.0, 5.0, 3.0, 27.0),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                )
            val id = repo.addEntry(original)
            val stored = repo.getEntry(id)!!
            repo.updateEntry(correctedEntry(stored, "3", MealType.LUNCH)!!)
            db.close()
            db = open()
            repo = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
            val saved = repo.getEntry(id)!!
            assertThat(saved.quantity.amount).isEqualTo(3.0)
            assertThat(saved.quantity.unit.label).isEqualTo("slice")
            assertThat(saved.quantity.grams).isEqualTo(60.0)
            assertThat(saved.macros).isEqualTo(Macros(225.0, 7.5, 4.5, 40.5))
            assertThat(saved.date).isEqualTo(stored.date)
            assertThat(saved.createdAt).isEqualTo(stored.createdAt)
            assertThat(repo.observeDay(saved.date).first().totals).isEqualTo(saved.macros)
            repo.deleteEntry(id)
            assertThat(runCatching { repo.updateEntry(saved) }.isFailure).isTrue()
            assertThat(repo.getEntry(id)).isNull()
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
