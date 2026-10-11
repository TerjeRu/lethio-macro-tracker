package com.lethio.macros.data.food

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.SettingsManager
import com.lethio.macros.core.SystemTimeProvider
import com.lethio.macros.data.user.UserDatabase
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export actual repository results and repeated warm timings, without a search replica. Run this
 * class ALONE: Context.dataStore is a process-wide delegate. Its first access here uses isolated
 * cache files, and no Activity is launched. The device's own settings, user.db and installed
 * nutrition.db are never opened. The host comparator checks the resulting IDs.
 */
@RunWith(AndroidJUnit4::class)
class SourceSearchBaselineTest {
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getDatabasePath(name: String): File =
            File(root, "databases/$name").apply { parentFile!!.mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("source-baseline-$name", mode)

        override fun getAssets(): AssetManager = baseContext.assets
    }

    private data class Case(val query: String, val country: String, val language: String)

    @Test
    fun exportRepositoryResultsAndWarmTimings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val className = SourceSearchBaselineTest::class.java.name
        val selectedClass = args.getString("class").orEmpty()
        assumeTrue(
            "Run SourceSearchBaselineTest alone so settings remain isolated",
            selectedClass == className ||
                selectedClass == "$className#exportRepositoryResultsAndWarmTimings",
        )
        val target = instrumentation.targetContext
        val root = File(target.cacheDir, "source-baseline-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(target, root)
        val settings = SettingsManager(context)
        val provider = FoodDatabaseProvider(context)
        val repository =
            FoodRepositoryImpl(
                provider,
                CompoundLexicon(context),
                TableLanguages(provider),
                Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
                    .setDriver(BundledSQLiteDriver())
                    .build()
                    .let { user ->
                        // Closed in finally; only an empty, real custom-food DAO is supplied.
                        userDatabase = user
                        user.customFoodDao()
                    },
                SystemTimeProvider(),
                settings,
            )
        val originalLocale = Locale.getDefault()
        try {
            settings.clearFoodLanguages()
            // Preference keys are EN/ES/NO/etc, not language tags; not every table language
            // has a UI picker entry. SYSTEM resolves the process locale through the real path.
            settings.setLanguagePreference(SettingsManager.LANGUAGE_SYSTEM)
            val fixtures =
                args.getString("fixtures")?.split(",")
                    ?: listOf(
                        "search-relevance-fixtures.tsv",
                        "staple-fixtures.tsv",
                        "source-integration-fixtures.tsv",
                    )
            val allCases = mutableListOf<Case>()
            // Supplemental same-phone A/B timing repeats can omit thousands of lookup/export
            // assertions. They use the identical scenario/warmup/batch/sample protocol and do
            // not replace the required full 403-case export gate (default mode).
            val timingsOnly = args.getString("timingsOnly") == "true"
            for (fixture in fixtures) {
                check(fixture.matches(Regex("[a-z-]+\\.tsv")))
                val cases =
                    instrumentation.context.assets.open(fixture).bufferedReader().useLines { lines
                        ->
                        lines
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .map { line ->
                                val cells = line.split('\t')
                                check(cells.size in 4..5)
                                Case(cells[0], cells[1], cells[2])
                            }
                            .toList()
                    }
                assertThat(cases).isNotEmpty()
                assertThat(cases.distinct()).hasSize(cases.size)
                allCases += cases
                if (timingsOnly) continue
                val exported = JSONArray()
                for (case in cases) {
                    Locale.setDefault(Locale.forLanguageTag(case.language))
                    assertThat(SettingsManager.languageFor(SettingsManager.LANGUAGE_SYSTEM))
                        .isEqualTo(case.language)
                    settings.setCountryPreference(case.country)
                    val foods = repository.search(case.query, 30)
                    assertThat(foods.map { it.ref.id }.distinct()).hasSize(foods.size)
                    val results = JSONArray()
                    for (food in foods) {
                        val id = checkNotNull(food.ref.id)
                        val stored = checkNotNull(provider.dao().getById(id, listOf("en"), ",en,"))
                        assertThat(repository.getByRef(food.ref)?.name).isEqualTo(food.name)
                        results.put(
                            JSONObject()
                                .put("id", id)
                                .put("source", stored.food.source)
                                .put("name", stored.food.name)
                                .put("display_name", food.name)
                        )
                    }
                    exported.put(
                        JSONObject()
                            .put("query", case.query)
                            .put("country", case.country)
                            .put("language", case.language)
                            .put("results", results)
                    )
                }
                File(root, fixture.removeSuffix(".tsv") + ".json").writeText(exported.toString(2))
            }
            // Fixed scenarios cover every existing country/language, broad queries, borrowing,
            // morphology and the two countries changing selection. Export every frozen case,
            // then warm each timing scenario and take 3 independent batches
            // of 20 searches: retain samples and report per-batch median/p95, not a single mean.
            val timings = JSONArray()
            val timingCases =
                instrumentation.context.assets
                    .open("source-benchmark-fixtures.tsv")
                    .bufferedReader()
                    .useLines { lines ->
                        lines
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .map { line ->
                                val cells = line.split('\t')
                                check(cells.size == 3)
                                Case(cells[0], cells[1], cells[2])
                            }
                            .toList()
                    }
            assertThat(timingCases).isNotEmpty()
            assertThat(allCases).containsAtLeastElementsIn(timingCases)
            for (case in timingCases) {
                Locale.setDefault(Locale.forLanguageTag(case.language))
                settings.setCountryPreference(case.country)
                repeat(3) { repository.search(case.query, 30) }
                repeat(3) { batch ->
                    val samples =
                        List(20) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            repository.search(case.query, 30)
                            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        }
                    val sorted = samples.sorted()
                    timings.put(
                        JSONObject()
                            .put("query", case.query)
                            .put("country", case.country)
                            .put("language", case.language)
                            .put("batch", batch)
                            .put("samples_ms", JSONArray(samples))
                            .put("median_ms", (sorted[9] + sorted[10]) / 2)
                            .put("p95_ms", sorted[ceil(sorted.size * .95).toInt() - 1])
                    )
                }
            }
            File(root, "timings.json")
                .writeText(
                    JSONObject()
                        .put("device", Build.MODEL)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("warmups", 3)
                        .put("batches", 3)
                        .put("samples_per_batch", 20)
                        .put("cases", timings)
                        .toString(2)
                )
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("sourceBaselineDir", root.absolutePath) },
            )
        } finally {
            Locale.setDefault(originalLocale)
            userDatabase?.close()
        }
    }

    private var userDatabase: UserDatabase? = null
}
