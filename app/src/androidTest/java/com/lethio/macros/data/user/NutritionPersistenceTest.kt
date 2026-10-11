package com.lethio.macros.data.user

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.food.FoodDatabaseProvider
import com.lethio.macros.data.toCustomFoodEntity
import com.lethio.macros.data.toDomain
import com.lethio.macros.domain.model.*
import com.lethio.macros.domain.nutrition.UnitConverter
import com.lethio.macros.ui.diary.correctedEntry
import com.lethio.macros.ui.settings.toCsvRow
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val date = LocalDate.of(2026, 10, 7)
    private val clock =
        object : TimeProvider {
            override fun today() = date

            override fun now() = now

            override fun currentTime() = LocalTime.NOON
        }
    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation,
            UserDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    private fun makeLegacy(name: String) {
        helper.createDatabase(name, 6).use { db ->
            db.execSQL(
                """INSERT INTO daily_logs (id, log_date, meal_type, food_ref_kind,
                food_ref_id, food_name, quantity, unit_label, grams, calories, protein_g,
                fat_g, carbs_g, created_at, updated_at) VALUES
                (7, '2026-10-07', 1, 'custom', 5, 'Synthetic old food', 2, 'slice', 40,
                80, 6, 2, 9, 100, 200)"""
                    .trimIndent()
            )
            db.execSQL(
                """INSERT INTO custom_foods (id, name, barcode, name_folded,
                kcal_100g, protein_100g, fat_100g, carbs_100g, created_at, updated_at)
                VALUES (5, 'Synthetic old food', '4012345678901', 'synthetic old food',
                200, 15, 5, 22.5, 100, 200)"""
                    .trimIndent()
            )
            db.execSQL(
                """INSERT INTO favorites (food_ref_kind, food_ref_id, food_name, sort_order, created_at)
                VALUES ('custom', 5, 'Synthetic old food', 0, 100)"""
                    .trimIndent()
            )
        }
    }

    @Test
    fun frameworkMigrationPreservesOldQuantityAndValues() {
        val name = "nutrition-framework-${UUID.randomUUID()}.db"
        try {
            makeLegacy(name)
            helper.runMigrationsAndValidate(name, 7, true, UserDatabase.MIGRATION_6_7).use { db ->
                db.query(
                        "SELECT id, quantity, unit_label, grams, basis_amount, nutrition_basis, calories, nutrition_metadata FROM daily_logs"
                    )
                    .use { row ->
                        assertThat(row.moveToFirst()).isTrue()
                        assertThat(row.getLong(0)).isEqualTo(7)
                        assertThat(row.getDouble(1)).isEqualTo(2.0)
                        assertThat(row.getString(2)).isEqualTo("slice")
                        assertThat(row.getDouble(3)).isEqualTo(40.0)
                        assertThat(row.getDouble(4)).isEqualTo(40.0)
                        assertThat(row.getString(5)).isEqualTo("g")
                        assertThat(row.getDouble(6)).isEqualTo(80.0)
                        assertThat(row.isNull(7)).isTrue()
                    }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun bundledDriverMigrationPreservesDiaryFavoritesAndBarcode() = runBlocking {
        val name = "nutrition-driver-${UUID.randomUUID()}.db"
        var db: UserDatabase? = null
        try {
            makeLegacy(name)
            db =
                Room.databaseBuilder(context, UserDatabase::class.java, name)
                    .setDriver(BundledSQLiteDriver())
                    .addMigrations(*UserDatabase.ALL_MIGRATIONS)
                    .build()
            val entry = requireNotNull(db.dailyLogDao().getById(7)).toDomain()
            assertThat(entry.macros).isEqualTo(Macros(80.0, 6.0, 2.0, 9.0))
            assertThat(entry.quantity)
                .isEqualTo(Quantity(2.0, MeasureUnit.Portion("slice", 20.0), 40.0))
            assertThat(db.favoriteDao().observeAll().first().single().foodRefId).isEqualTo(5)
            assertThat(db.customFoodDao().findByBarcode("4012345678901")?.nutritionBasis)
                .isEqualTo("g")
            val next =
                LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
                    .addEntry(entry.copy(id = 0))
            assertThat(next).isGreaterThan(7)
        } finally {
            db?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun nativeMlQualificationSnapshotsSurviveRestartCorrectionAndCatalogueReplacement() =
        runBlocking {
            val root =
                File(context.cacheDir, "nutrition-roundtrip-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val isolated =
                object : ContextWrapper(context) {
                    override fun getDatabasePath(name: String) = File(root, name)
                }
            val provider =
                object : FoodDatabaseProvider(isolated) {
                    override fun openSeedAsset() =
                        instrumentation.context.assets.open("nutrition-fixture.db")
                }
            fun openUser() =
                Room.databaseBuilder(isolated, UserDatabase::class.java, "user-test.db")
                    .setDriver(BundledSQLiteDriver())
                    .build()
            var db = openUser()
            try {
                val food =
                    requireNotNull(provider.dao().getById(999, listOf("en"), ",en,")).toDomain()
                assertThat(food.ref).isEqualTo(FoodRef.Bundled(999))
                assertThat(food.name).isEqualTo("Synthetic volume food")
                assertThat(food.availableUnits).containsExactly(MeasureUnit.Milliliters)
                val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, food))
                val snapshot = requireNotNull(food.nutritionFor(q))
                val logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
                val entry =
                    LogEntry(
                        0,
                        date,
                        MealType.LUNCH,
                        food.ref,
                        food.name,
                        quantity = q,
                        macros = snapshot,
                        createdAt = now,
                        updatedAt = now,
                    )
                val id = logs.addEntry(entry)
                logs.toggleFavorite(entry)
                val custom = db.customFoodDao().insert(food.toCustomFoodEntity(now))
                assertThat(db.customFoodDao().getById(custom)?.toDomain()?.per100g)
                    .isEqualTo(food.per100g)
                db.close()
                db = openUser()
                val reopened = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
                val saved = requireNotNull(reopened.getEntry(id))
                assertThat(saved.quantity).isEqualTo(q)
                assertThat(saved.quantity.grams).isNull()
                assertThat(saved.macros).isEqualTo(snapshot)
                assertThat(saved.macros.calories).isEqualTo(100.0)
                assertThat(saved.macros.nutrient(Nutrient.PROTEIN).sourceValueType).isEqualTo("BE")
                assertThat(saved.macros.nutrient(Nutrient.FAT).qualifiers)
                    .contains(NutrientQualifier.BELOW_DETECTION)
                assertThat(saved.macros.nutrient(Nutrient.SODIUM).upperBound).isEqualTo(1.0)
                assertThat(saved.macros.nutrient(Nutrient.SODIUM).upperInclusive).isFalse()
                assertThat(reopened.recentFoods(10).single().foodRef).isEqualTo(food.ref)
                assertThat(reopened.observeFavorites().first().single().foodRef).isEqualTo(food.ref)
                val row = saved.toCsvRow()
                assertThat(row).doesNotContain("null")
                assertThat(row).contains("\"ml\"")
                assertThat(row).contains("BELOW_DETECTION")
                assertThat(row).contains("upperInclusive")
                val replacement = File(root, "replacement.db")
                instrumentation.context.assets.open("nutrition-replacement.db").use { input ->
                    replacement.outputStream().use(input::copyTo)
                }
                provider.replaceWith(replacement)
                val replaced =
                    requireNotNull(provider.dao().getById(999, listOf("en"), ",en,")).toDomain()
                assertThat(replaced.per100g.calories).isEqualTo(400.0)
                assertThat(replaced.densityGPerMl).isEqualTo(0.8)
                assertThat(reopened.getEntry(id)?.macros).isEqualTo(snapshot)
                val corrected = requireNotNull(correctedEntry(saved, "100", MealType.DINNER))
                reopened.updateEntry(corrected)
                assertThat(reopened.getEntry(id)?.macros?.calories).isEqualTo(40.0)
                assertThat(reopened.getEntry(id)?.quantity?.grams).isNull()
                assertThat(reopened.getEntry(id)?.quantity?.basisAmount).isEqualTo(100.0)
                assertThat(reopened.getEntry(id)?.macros?.nutrient(Nutrient.SODIUM)?.upperBound)
                    .isEqualTo(0.4)
            } finally {
                db.close()
            }
        }

    @Test
    fun historyAndDailyTotalsUseSameQualifiedKnownSubtotal() = runBlocking {
        val name = "nutrition-totals-${UUID.randomUUID()}.db"
        val db =
            Room.databaseBuilder(context, UserDatabase::class.java, name)
                .setDriver(BundledSQLiteDriver())
                .build()
        try {
            val logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
            val qualified =
                Macros(100.0)
                    .withNutrients(
                        mapOf(
                            Nutrient.FAT to
                                NutrientValue(0.0, setOf(NutrientQualifier.BELOW_DETECTION))
                        )
                    )
            val missing = Macros(40.0).withNutrients(mapOf(Nutrient.FAT to NutrientValue()))
            for (macros in listOf(qualified, missing, Macros(60.0, fatG = 12.0))) {
                logs.addEntry(
                    LogEntry(
                        0,
                        date,
                        MealType.LUNCH,
                        FoodRef.QuickAdd,
                        "Synthetic total",
                        quantity = Quantity.ofGrams(100.0),
                        macros = macros,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
            val daily = logs.observeDay(date).first().totals
            val history =
                DiaryHistoryRepositoryImpl(db.dailyLogDao(), db.goalsDao())
                    .observeWindow(DiaryHistoryWindow(date, DiaryHistoryPeriod.SEVEN_DAYS))
                    .first()
                    .last()
                    .loggedIntake
            assertThat(history).isEqualTo(daily)
            assertThat(daily.calories).isEqualTo(200.0)
            assertThat(daily.fatG).isEqualTo(12.0)
            assertThat(daily.isComplete(Nutrient.FAT)).isFalse()
            assertThat(daily.isComplete(Nutrient.CALORIES)).isTrue()
            assertThat(daily.nutrient(Nutrient.FAT).qualifiers)
                .contains(NutrientQualifier.BELOW_DETECTION)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
