package com.lethio.macros.data.user

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a migration through **the driver the app actually ships**.
 *
 * With a driver configured Room calls `migrate(SQLiteConnection)`, but `MigrationTestHelper`
 * exercises `migrate(SupportSQLiteDatabase)`. Migration 5→6 reads rows, so a wrong column index on
 * the connection path would pass the helper tests and corrupt the backfill on a real upgrade. This
 * opens a real [UserDatabase] with [BundledSQLiteDriver] against a v5 file.
 */
@RunWith(AndroidJUnit4::class)
class UserDatabaseDriverMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            UserDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrate5To6_backfillsThroughTheBundledDriverToo() {
        context.deleteDatabase(DB_NAME)

        // A v5 database with rows that only fold correctly if foldForSearch actually ran: a grave
        // accent, a stroked o, a ligature, and a null brand to exercise bindNull.
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO custom_foods
                    (id, name, brand, barcode, source, kcal_100g, protein_100g, fat_100g,
                     carbs_100g, created_at, updated_at)
                VALUES
                    (1, 'Kaffè', 'Bjørn', NULL, NULL, 2.0, 0.1, 0.0, 0.4, 500, 500),
                    (2, 'Œuf, cru', NULL, NULL, NULL, 143.0, 12.6, 9.8, 0.7, 500, 500)
                """
                    .trimIndent()
            )
        }

        val database =
            Room.databaseBuilder(context, UserDatabase::class.java, DB_NAME)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*UserDatabase.ALL_MIGRATIONS)
                .build()

        try {
            // Reading through the DAO forces Room to open, and opening is what migrates. If the
            // connection path threw NotImplementedError, it would be here.
            val found = runBlocking { database.customFoodDao().search("kaffe", 10) }

            // The whole point: the row is reachable by its folded name, which means the backfill
            // ran on this path and wrote something real rather than leaving the column empty.
            assertThat(found.map { it.name }).containsExactly("Kaffè")
            assertThat(found.single().nameFolded).isEqualTo("kaffe")
            assertThat(found.single().brandFolded).isEqualTo("bjorn")

            // bindNull's row, and the ligature foldForSearch exists for.
            val ligature = runBlocking { database.customFoodDao().search("oeuf", 10) }
            assertThat(ligature.map { it.name }).containsExactly("Œuf, cru")
            assertThat(ligature.single().brandFolded).isNull()
        } finally {
            database.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    private companion object {
        const val DB_NAME = "driver-migration-test.db"
    }
}
