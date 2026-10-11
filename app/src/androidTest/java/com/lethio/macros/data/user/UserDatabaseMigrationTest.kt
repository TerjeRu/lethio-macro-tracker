package com.lethio.macros.data.user

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User data must survive an app update. `UserDatabase` has no destructive fallback, so every
 * version step gets a test here.
 */
@RunWith(AndroidJUnit4::class)
class UserDatabaseMigrationTest {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            UserDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    @Test
    fun migrate1To2_changesOnlyLegacyBundledSourcesAndPreservesEveryRow() {
        val sources = listOf("db", "bundled", "custom", "quick")
        val expected = mutableMapOf<String, List<List<String?>>>()
        helper.createDatabase(DB_NAME, 1).use { db ->
            sources.forEachIndexed { index, source ->
                val id = index + 1
                db.execSQL(
                    """
                    INSERT INTO daily_logs
                        (id, log_date, meal_type, food_id, food_source, food_name,
                         amount, amount_unit, calories, protein, fat, carbs, created_at)
                    VALUES (?, '2026-09-29', 1, ?, ?, ?, 80.5, 'g', 303.0, 11.0, 6.2, 50.0, ?)
                    """
                        .trimIndent(),
                    arrayOf<Any?>(
                        id,
                        if (source == "quick") null else id * 10,
                        source,
                        "Food $id",
                        id * 1000,
                    ),
                )
                db.execSQL(
                    "INSERT INTO favorites (id, food_id, food_source, food_name, sort_order, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        id,
                        if (source == "quick") null else id * 10,
                        source,
                        "Food $id",
                        index,
                        id * 1000,
                    ),
                )
            }
            for (table in listOf("daily_logs", "favorites")) {
                db.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                    val sourceColumn = cursor.getColumnIndexOrThrow("food_source")
                    expected[table] = buildList {
                        while (cursor.moveToNext()) {
                            add(
                                List(cursor.columnCount) { column ->
                                    val value = cursor.getString(column)
                                    if (column == sourceColumn && value == "db") "bundled"
                                    else value
                                }
                            )
                        }
                    }
                }
            }
        }
        helper.runMigrationsAndValidate(DB_NAME, 2, true, UserDatabase.MIGRATION_1_2).use { db ->
            for (table in listOf("daily_logs", "favorites")) {
                db.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                    val actual = buildList {
                        while (cursor.moveToNext()) {
                            add(List(cursor.columnCount) { cursor.getString(it) })
                        }
                    }
                    assertThat(actual).containsExactlyElementsIn(expected.getValue(table)).inOrder()
                }
            }
        }
    }

    @Test
    fun migrate2To3_preservesEveryLoggedEntry() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs
                    (log_date, meal_type, food_id, food_source, food_name,
                     amount, amount_unit, calories, protein, fat, carbs, created_at)
                VALUES
                    ('2026-08-10', 1, 42, 'bundled', 'Porridge oats',
                     80.0, 'g', 303.0, 11.0, 6.2, 50.0, 1000),
                    ('2026-08-10', 2, NULL, 'quick', 'Canteen lunch',
                     1.0, 'serving', 650.0, 30.0, 25.0, 70.0, 2000),
                    ('2026-08-11', 0, 7, 'custom', 'My protein shake',
                     300.0, 'g', 180.0, 30.0, 2.0, 8.0, 3000)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM daily_logs").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(3)
        }

        // Values carry across intact, and the grams-only `amount` becomes both the user-facing
        // quantity and the canonical mass.
        db.query(
                "SELECT food_name, quantity, unit_label, grams, calories, protein_g, " +
                    "food_ref_kind, food_ref_id FROM daily_logs WHERE food_name = 'Porridge oats'"
            )
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getDouble(1)).isEqualTo(80.0)
                assertThat(c.getString(2)).isEqualTo("g")
                assertThat(c.getDouble(3)).isEqualTo(80.0)
                assertThat(c.getDouble(4)).isEqualTo(303.0)
                assertThat(c.getDouble(5)).isEqualTo(11.0)
                assertThat(c.getString(6)).isEqualTo("bundled")
                assertThat(c.getLong(7)).isEqualTo(42L)
            }
    }

    @Test
    fun migrate2To3_renumbersSnackFromZeroToFour() {
        // meal_type 0 collided with the column default, so rows written without an explicit meal
        // silently became snacks. Existing snacks must stay snacks under the new numbering.
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs
                    (log_date, meal_type, food_source, food_name,
                     amount, amount_unit, calories, protein, fat, carbs, created_at)
                VALUES ('2026-08-11', 0, 'quick', 'Late snack',
                        1.0, 'serving', 200.0, 5.0, 10.0, 20.0, 4000)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT meal_type FROM daily_logs WHERE food_name = 'Late snack'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(4)
        }
        db.query("SELECT COUNT(*) FROM daily_logs WHERE meal_type = 0").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate2To3_quickAddEntriesLoseTheirMeaninglessFoodId() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs
                    (log_date, meal_type, food_id, food_source, food_name,
                     amount, amount_unit, calories, protein, fat, carbs, created_at)
                VALUES ('2026-08-11', 1, 999, 'quick', 'Typed in',
                        1.0, 'serving', 100.0, 1.0, 1.0, 1.0, 5000)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT food_ref_id FROM daily_logs WHERE food_name = 'Typed in'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
    }

    @Test
    fun migrate2To3_backdatesExistingGoalsSoHistoryKeepsItsTargets() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO goals (id, calories_target, protein_target, fat_target, carbs_target)
                VALUES (1, 2400.0, 180.0, 70.0, 260.0)
                """
                    .trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO daily_logs
                    (log_date, meal_type, food_source, food_name,
                     amount, amount_unit, calories, protein, fat, carbs, created_at)
                VALUES ('2026-06-01', 1, 'quick', 'Oldest entry',
                        1.0, 'serving', 100.0, 1.0, 1.0, 1.0, 100)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT effective_from, calories_target, protein_target_g, source FROM goals")
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                // Backdated to the first logged day: no historical day is left ungoverned, and none
                // is rescored against targets that did not apply at the time.
                assertThat(c.getString(0)).isEqualTo("2026-06-01")
                assertThat(c.getDouble(1)).isEqualTo(2400.0)
                assertThat(c.getDouble(2)).isEqualTo(180.0)
                assertThat(c.getString(3)).isEqualTo("manual")
            }
    }

    @Test
    fun migrate2To3_dropsUnreachableFavoritesAndKeepsTheRest() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO favorites (food_id, food_source, food_name, sort_order, created_at)
                VALUES (42, 'bundled', 'Porridge oats', 0, 1000),
                       (NULL, 'quick', 'Unreachable favourite', 1, 2000)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        // The NULL-id row could never be deleted anyway -- "WHERE food_id = ?" never matches NULL
        // -- so it is dropped as unreachable rather than migrated into a table that forbids it.
        db.query("SELECT food_name, food_ref_kind, food_ref_id FROM favorites").use { c ->
            assertThat(c.count).isEqualTo(1)
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Porridge oats")
            assertThat(c.getString(1)).isEqualTo("bundled")
            assertThat(c.getLong(2)).isEqualTo(42L)
        }
    }

    @Test
    fun migrate2To3_promotesCustomFoodServingSizeIntoARealServingRow() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO custom_foods
                    (description, brand_owner, gtin_upc, calories_100g, protein_100g,
                     fat_100g, carbs_100g, serving_size, serving_unit, created_at)
                VALUES ('Sourdough loaf', 'Local bakery', '1234567890123',
                        250.0, 9.0, 2.0, 48.0, 45.0, 'slice', 1000)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT name, brand, barcode, kcal_100g FROM custom_foods").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Sourdough loaf")
            assertThat(c.getString(1)).isEqualTo("Local bakery")
            assertThat(c.getString(2)).isEqualTo("1234567890123")
            assertThat(c.getDouble(3)).isEqualTo(250.0)
        }

        db.query("SELECT label, grams, is_default FROM custom_food_servings").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("slice")
            assertThat(c.getDouble(1)).isEqualTo(45.0)
            assertThat(c.getInt(2)).isEqualTo(1)
        }
    }

    @Test
    fun migrate2To3_createsTheNewProfileAndWeightTables() {
        helper.createDatabase(DB_NAME, 2).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, UserDatabase.MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM profile").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM weight_entries").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate3To4_addsBodyFatAndKeepsTheProfile() {
        // The goal calculator's Katch-McArdle path needs a body fat percentage on `profile`. An
        // ADD COLUMN cannot lose data, but it can be spelled wrong, and Room validates the whole
        // schema afterwards rather than just the column.
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO profile
                    (id, sex, birth_date, height_cm, activity_level, goal_direction,
                     rate_kg_per_week, mass_unit, energy_unit, onboarded_at)
                VALUES (1, 'female', '1991-03-04', 165.0, 'moderate', 'lose',
                        0.5, 'metric', 'kcal', 1700)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 4, true, UserDatabase.MIGRATION_3_4)

        db.query(
                "SELECT sex, height_cm, rate_kg_per_week, body_fat_percent FROM profile WHERE id = 1"
            )
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("female")
                assertThat(c.getDouble(1)).isEqualTo(165.0)
                assertThat(c.getDouble(2)).isEqualTo(0.5)
                // Null, not zero. Nobody has measured this, and 0% body fat is not a body -- a
                // default
                // here would have been an invented measurement that the calculator would then
                // trust.
                assertThat(c.isNull(3)).isTrue()
            }
    }

    @Test
    fun migrate4To5_addsSourceAndLeavesExistingFoodsAsTheUsersOwn() {
        // Open Food Facts lookups are saved as custom foods, so the table has to be able to
        // say where a row's numbers came from. ADD COLUMN cannot lose data, but it can be spelled
        // wrong, and Room validates the whole schema afterwards rather than just the column.
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO custom_foods
                    (id, name, brand, barcode, kcal_100g, protein_100g, fat_100g, carbs_100g,
                     created_at, updated_at)
                VALUES (1, 'Grandmother''s stew', NULL, NULL, 120.0, 8.0, 4.0, 12.0, 500, 500)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 5, true, UserDatabase.MIGRATION_4_5)

        db.query("SELECT name, kcal_100g, source FROM custom_foods WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Grandmother's stew")
            assertThat(c.getDouble(1)).isEqualTo(120.0)
            // Null, not 'user'. Everything saved before this migration was typed by the person
            // using the app, and the mapper reads an absent source as exactly that -- writing a
            // literal here would be inventing provenance for rows that never had any.
            assertThat(c.isNull(2)).isTrue()
        }
    }

    @Test
    fun migrate5To6_backfillsFoldedColumnsSoExistingFoodsStayFindable() {
        // The whole risk: adding the columns is trivial; leaving existing rows empty
        // would make every custom food created before this migration permanently unfindable,
        // which is strictly worse than the bug it fixes -- the reader's own foods are the ones
        // they are most certain are in there.
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO custom_foods
                    (id, name, brand, barcode, source, kcal_100g, protein_100g, fat_100g,
                     carbs_100g, created_at, updated_at)
                VALUES
                    (1, 'Kaffè', 'Bjørn & Sønn', NULL, NULL, 2.0, 0.1, 0.0, 0.4, 500, 500),
                    (2, 'Œuf, cru', NULL, NULL, NULL, 143.0, 12.6, 9.8, 0.7, 500, 500),
                    (3, 'Weißbrot', NULL, NULL, NULL, 250.0, 8.0, 3.0, 48.0, 500, 500)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 6, true, UserDatabase.MIGRATION_5_6)

        db.query("SELECT id, name, name_folded, brand, brand_folded FROM custom_foods ORDER BY id")
            .use { c ->
                assertThat(c.moveToNext()).isTrue()
                // Display text is untouched; only the searchable copy is folded.
                assertThat(c.getString(1)).isEqualTo("Kaffè")
                assertThat(c.getString(2)).isEqualTo("kaffe")
                assertThat(c.getString(3)).isEqualTo("Bjørn & Sønn")
                // ø folds to o -- the stroked-letter rule the name-folding pair already settled.
                assertThat(c.getString(4)).isEqualTo("bjorn & sonn")

                assertThat(c.moveToNext()).isTrue()
                // The ligature case foldForSearch exists for; NFKD alone does not split Œ.
                assertThat(c.getString(2)).isEqualTo("oeuf, cru")
                assertThat(c.isNull(4)).isTrue()

                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getString(2)).isEqualTo("weissbrot")
            }
    }

    @Test
    fun migrate5To6_leavesATableWithNoCustomFoodsAlone() {
        // The backfill reads before it writes, and the common case is an empty table. A migration
        // that only works when there is something to fold is a migration nobody tested empty.
        helper.createDatabase(DB_NAME, 5).use { /* no rows */ }

        val db = helper.runMigrationsAndValidate(DB_NAME, 6, true, UserDatabase.MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM custom_foods").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate1To6_runsTheWholeChain() {
        // Anyone still on v1 has to arrive at the current version in one launch, through every
        // migration in order. 5->6 backfills in Kotlin, so this also proves the chain survives a
        // migration that reads rows rather than only reshaping them.
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs
                    (log_date, meal_type, food_id, food_source, food_name,
                     amount, amount_unit, calories, protein, fat, carbs, created_at)
                VALUES ('2026-05-01', 1, 5, 'db', 'Ancient entry',
                        100.0, 'g', 200.0, 10.0, 5.0, 25.0, 500)
                """
                    .trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 6, true, *UserDatabase.ALL_MIGRATIONS)

        db.query(
                "SELECT food_ref_kind, food_name FROM daily_logs WHERE food_name = 'Ancient entry'"
            )
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                // 'db' -> 'bundled' happened in 1->2 and must survive 2->3.
                assertThat(c.getString(0)).isEqualTo("bundled")
            }
    }
}
