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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run explicitly against the scratch NEVO APK and independently generated raw expectations. */
@RunWith(AndroidJUnit4::class)
class NevoCatalogueTest {
    @Test
    fun allPublisherFoodsMapAndNativeVolumeSnapshotsSurviveRestartAndCorrection() = runBlocking {
        assumeTrue(
            "Requires the scratch NEVO catalogue",
            InstrumentationRegistry.getArguments().getString("nevoVerification") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val expected = JSONArray(File(target.filesDir, "nevo-expectations.json").readText())
        assertThat(expected.length()).isEqualTo(2328)
        val root =
            File(target.cacheDir, "nevo-verification-${UUID.randomUUID()}").apply { mkdirs() }
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
        var volume: Food? = null
        var volumeCount = 0
        try {
            val dao = provider.dao()
            for (index in 0 until expected.length()) {
                val row = expected.getJSONObject(index)
                val food =
                    requireNotNull(dao.getById(row.getLong("id"), listOf("nl"), ",nl,")).toDomain()
                assertThat(food.name).isEqualTo(row.getString("name"))
                assertThat(food.provenance).isEqualTo(DataSource.NEVO)
                val basis = NutritionBasis.fromCode(row.getString("basis"))
                assertThat(food.nutritionBasis).isEqualTo(basis)
                assertThat(food.densityGPerMl).isNull()
                val unit =
                    if (basis == NutritionBasis.VOLUME) MeasureUnit.Milliliters
                    else MeasureUnit.Grams
                if (basis == NutritionBasis.VOLUME) {
                    volumeCount++
                    volume = food
                    assertThat(food.availableUnits).containsExactly(MeasureUnit.Milliliters)
                }
                val quantity = requireNotNull(UnitConverter.resolve(250.0, unit, food))
                val snapshot = requireNotNull(food.nutritionFor(quantity))
                for (kind in Nutrient.entries) {
                    val value = food.per100g.nutrient(kind)
                    val raw = row.getJSONObject("nutrients").getJSONObject(kind.name)
                    if (raw.isNull("value")) assertThat(value.value).isNull()
                    else assertThat(value.value).isWithin(1e-12).of(raw.getDouble("value"))
                    assertThat(value.sourceValue).isEqualTo(raw.getString("sourceValue"))
                    assertThat(value.sourceUnit).isEqualTo(raw.getString("sourceUnit"))
                    assertThat(value.sourceValueType)
                        .isEqualTo(raw.optString("sourceValueType").ifEmpty { null })
                    assertThat(value.qualifiers.map { it.name })
                        .containsExactlyElementsIn(
                            raw.getJSONArray("qualifiers").let { a ->
                                (0 until a.length()).map(a::getString)
                            }
                        )
                    assertThat(value.sourceOrigin?.dataset).isEqualTo("nevo")
                    assertThat(value.sourceOrigin?.foodId).isEqualTo(row.getString("publisherId"))
                    assertThat(value.sourceOrigin?.version).isEqualTo("NEVO-Online 2025 9.0")
                    assertThat(snapshot.nutrient(kind).sourceOrigin).isEqualTo(value.sourceOrigin)
                    assertThat(snapshot.nutrient(kind).value).isEqualTo(value.value?.times(2.5))
                }
            }
            assertThat(volumeCount).isEqualTo(53)
            val food = requireNotNull(volume)
            val q = requireNotNull(UnitConverter.resolve(250.0, MeasureUnit.Milliliters, food))
            val snapshot = requireNotNull(food.nutritionFor(q))
            val logs = LogRepositoryImpl(user.dailyLogDao(), user.favoriteDao(), clock)
            val id =
                logs.addEntry(
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
                )
            user.close()
            user = openUser()
            val reopened = LogRepositoryImpl(user.dailyLogDao(), user.favoriteDao(), clock)
            val saved = requireNotNull(reopened.getEntry(id))
            assertThat(saved.quantity.grams).isNull()
            assertThat(saved.macros).isEqualTo(snapshot)
            // Replace the entire NEVO catalogue with a real unrelated tiny Room catalogue.
            val replacement = File(root, "replacement.db")
            instrumentation.context.assets.open("nutrition-replacement.db").use { input ->
                replacement.outputStream().use(input::copyTo)
            }
            provider.replaceWith(replacement)
            assertThat(reopened.getEntry(id)?.macros).isEqualTo(snapshot)
            reopened.updateEntry(requireNotNull(correctedEntry(saved, "100", MealType.DINNER)))
            val corrected = requireNotNull(reopened.getEntry(id))
            assertThat(corrected.macros.calories).isWithin(1e-12).of(food.per100g.calories)
            assertThat(corrected.macros.nutrient(Nutrient.CALORIES).sourceOrigin)
                .isEqualTo(food.per100g.nutrient(Nutrient.CALORIES).sourceOrigin)
        } finally {
            user.close()
            root.deleteRecursively()
        }
    }
}
