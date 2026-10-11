package com.lethio.macros.data.food

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.toDomain
import com.lethio.macros.data.user.LogRepositoryImpl
import com.lethio.macros.data.user.UserDatabase
import com.lethio.macros.domain.model.*
import com.lethio.macros.domain.nutrition.UnitConverter
import com.lethio.macros.ui.diary.correctedEntry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit later device gate. Expectations come from raw files, never the production loader. */
@RunWith(AndroidJUnit4::class)
class FoodfilesCatalogueTest {
    @Test
    fun allPublisherFoodsHaveRegisteredProvenance() = runBlocking {
        assumeTrue(
            "Requires the scratch FOODfiles catalogue and raw expectations",
            InstrumentationRegistry.getArguments().getString("foodfilesVerification") == "true",
        )
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val expected =
            JSONObject(File(target.filesDir, "foodfiles-expectations.json").readText())
                .getJSONArray("foods")
        assertThat(expected.length()).isEqualTo(2854)
        val root =
            File(target.cacheDir, "foodfiles-provenance-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this

                override fun getDatabasePath(name: String) = File(root, name)
            }
        try {
            val dao = FoodDatabaseProvider(context).dao()
            for (index in 0 until expected.length()) {
                val food =
                    requireNotNull(
                            dao.getById(
                                expected.getJSONObject(index).getLong("id"),
                                listOf("en"),
                                ",en,",
                            )
                        )
                        .toDomain()
                assertThat(food.provenance?.value).isEqualTo("foodfiles")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun allPublisherFoodsAndMeasuredQuantitiesSurviveDiaryRestartAndCatalogueReplacement() =
        runBlocking {
            assumeTrue(
                "Requires the scratch FOODfiles catalogue and raw expectations",
                InstrumentationRegistry.getArguments().getString("foodfilesVerification") == "true",
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            val expectations =
                JSONObject(File(target.filesDir, "foodfiles-expectations.json").readText())
            val digest = MessageDigest.getInstance("SHA-256")
            target.assets.open("seed.db").use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            assertThat(digest.digest().joinToString("") { "%02x".format(it) })
                .isEqualTo(expectations.getString("catalogueSha256"))
            val expected = expectations.getJSONArray("foods")
            assertThat(expected.length()).isEqualTo(2854)
            val root =
                File(target.cacheDir, "foodfiles-verification-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val context =
                object : ContextWrapper(target) {
                    override fun getApplicationContext(): Context = this

                    override fun getDatabasePath(name: String) = File(root, name)
                }
            val provider = FoodDatabaseProvider(context)
            fun openUser() =
                Room.databaseBuilder(context, UserDatabase::class.java, "user-test.db")
                    .setDriver(BundledSQLiteDriver())
                    .build()
            var user = openUser()
            val date = LocalDate.of(2026, 10, 7)
            val now = Instant.parse("2026-10-07T12:00:00Z")
            val clock =
                object : TimeProvider {
                    override fun today() = date

                    override fun now() = now

                    override fun currentTime() = LocalTime.NOON
                }
            val special = mutableMapOf<String, Food>()
            try {
                val dao = provider.dao()
                for (index in 0 until expected.length()) {
                    val row = expected.getJSONObject(index)
                    val fid = row.getString("publisherId")
                    val food =
                        requireNotNull(dao.getById(row.getLong("id"), listOf("en"), ",en,"))
                            .toDomain(dao.servingsFor(row.getLong("id")))
                    assertThat(food.name).isEqualTo(row.getString("name"))
                    // The separate provenance test keeps the registration gate strict without
                    // preventing independent verification of every nutrient and diary snapshot.
                    assertThat(food.nutritionBasis).isEqualTo(NutritionBasis.MASS)
                    if (row.isNull("density")) {
                        assertThat(food.densityGPerMl).isNull()
                        assertThat(food.availableUnits).doesNotContain(MeasureUnit.Milliliters)
                        assertThat(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, food))
                            .isNull()
                    } else {
                        assertThat(food.densityGPerMl).isWithin(1e-12).of(row.getDouble("density"))
                        assertThat(food.availableUnits).contains(MeasureUnit.Milliliters)
                    }
                    val portions = row.getJSONArray("portions")
                    assertThat(food.servings.map { it.label to it.grams })
                        .containsExactlyElementsIn(
                            (0 until portions.length()).map { i ->
                                portions.getJSONObject(i).let {
                                    it.getString("label") to it.getDouble("grams")
                                }
                            }
                        )
                    val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Grams, food))
                    val snapshot = requireNotNull(food.nutritionFor(q))
                    for (kind in Nutrient.entries) {
                        val value = food.per100g.nutrient(kind)
                        val raw = row.getJSONObject("nutrients").getJSONObject(kind.name)
                        if (raw.isNull("value")) assertThat(value.value).isNull()
                        else assertThat(value.value).isWithin(1e-12).of(raw.getDouble("value"))
                        assertThat(value.sourceValue)
                            .isEqualTo(
                                if (raw.isNull("sourceValue")) null
                                else raw.getString("sourceValue")
                            )
                        assertThat(value.sourceUnit).isEqualTo(raw.getString("sourceUnit"))
                        assertThat(value.sourceValueType)
                            .isEqualTo(raw.getString("sourceValueType").ifEmpty { null })
                        val qualifiers = raw.getJSONArray("qualifiers")
                        assertThat(value.qualifiers.map { it.name })
                            .containsExactlyElementsIn(
                                (0 until qualifiers.length()).map(qualifiers::getString)
                            )
                        val references = raw.getJSONArray("references")
                        assertThat(value.sourceOrigin)
                            .isEqualTo(
                                NutrientOrigin(
                                    "foodfiles",
                                    "FOODfiles 2024 v1",
                                    fid,
                                    raw.getString("nutrientCode"),
                                    (0 until references.length()).map { i ->
                                        references.getJSONObject(i).let {
                                            NutrientReference(
                                                it.getString("code"),
                                                it.getString("reference"),
                                            )
                                        }
                                    },
                                )
                            )
                        assertThat(snapshot.nutrient(kind).sourceOrigin)
                            .isEqualTo(value.sourceOrigin)
                        assertThat(snapshot.nutrient(kind).value).isEqualTo(value.value?.times(2.5))
                    }
                    if (fid in setOf("F1110", "F10011", "A1185", "M1148")) special[fid] = food
                }
                val milk = requireNotNull(special["F1110"])
                val ml = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, milk))
                assertThat(ml.grams).isWithin(1e-12).of(252.5)
                val cup = milk.servings.single { it.label.contains("250 mL") }
                assertThat(cup.grams).isEqualTo(253.0)
                val cupQuantity =
                    requireNotNull(
                        UnitConverter.resolve(1.0, MeasureUnit.Portion(cup.label, cup.grams), milk)
                    )
                assertThat(requireNotNull(milk.nutritionFor(ml)).calories)
                    .isWithin(1e-10)
                    .of(milk.per100g.calories * 2.525)
                assertThat(requireNotNull(milk.nutritionFor(cupQuantity)).calories)
                    .isWithin(1e-10)
                    .of(milk.per100g.calories * 2.53)
                val undecidable = requireNotNull(special["F10011"])
                assertThat(undecidable.per100g.nutrient(Nutrient.FIBRE).reportedValue).isNull()
                assertThat(undecidable.per100g.isComplete(Nutrient.FIBRE)).isFalse()
                assertThat(requireNotNull(special["A1185"]).per100g.nutrient(Nutrient.FIBRE).value)
                    .isNull()
                val beef = requireNotNull(special["M1148"])
                assertThat(beef.densityGPerMl).isNull()
                assertThat(beef.servings.single { it.label == "1 cup (250 mL) diced " }.grams)
                    .isEqualTo(215.0)
                val logs = LogRepositoryImpl(user.dailyLogDao(), user.favoriteDao(), clock)
                val saved =
                    listOf(
                            milk to ml,
                            milk to cupQuantity,
                            undecidable to
                                requireNotNull(
                                    UnitConverter.resolve(100.0, MeasureUnit.Grams, undecidable)
                                ),
                        )
                        .map { (food, quantity) ->
                            val snapshot = requireNotNull(food.nutritionFor(quantity))
                            val id =
                                logs.addEntry(
                                    LogEntry(
                                        0,
                                        date,
                                        MealType.LUNCH,
                                        food.ref,
                                        food.name,
                                        quantity = quantity,
                                        macros = snapshot,
                                        createdAt = now,
                                        updatedAt = now,
                                    )
                                )
                            Triple(id, quantity, snapshot)
                        }
                user.close()
                user = openUser()
                val reopened = LogRepositoryImpl(user.dailyLogDao(), user.favoriteDao(), clock)
                for ((id, quantity, snapshot) in saved) {
                    val entry = requireNotNull(reopened.getEntry(id))
                    assertThat(entry.quantity).isEqualTo(quantity)
                    assertThat(entry.macros).isEqualTo(snapshot)
                }
                val replacement = File(root, "replacement.db")
                instrumentation.context.assets.open("nutrition-replacement.db").use { input ->
                    replacement.outputStream().use(input::copyTo)
                }
                provider.replaceWith(replacement)
                for ((id, _, snapshot) in saved) assertThat(reopened.getEntry(id)?.macros)
                    .isEqualTo(snapshot)
                val milkEntry = requireNotNull(reopened.getEntry(saved.first().first))
                reopened.updateEntry(
                    requireNotNull(correctedEntry(milkEntry, "100", MealType.DINNER))
                )
                val corrected = requireNotNull(reopened.getEntry(milkEntry.id))
                assertThat(corrected.quantity.grams).isWithin(1e-12).of(101.0)
                assertThat(corrected.macros.calories)
                    .isWithin(1e-10)
                    .of(milk.per100g.calories * 1.01)
                assertThat(corrected.macros.nutrient(Nutrient.CALORIES).sourceOrigin)
                    .isEqualTo(milk.per100g.nutrient(Nutrient.CALORIES).sourceOrigin)
            } finally {
                user.close()
                root.deleteRecursively()
            }
        }
}
