package com.lethio.macros.ui.log

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.R
import com.lethio.macros.SettingsManager
import com.lethio.macros.core.CurrentDiaryDate
import com.lethio.macros.core.TimeProvider
import com.lethio.macros.data.food.*
import com.lethio.macros.data.toCustomFoodEntity
import com.lethio.macros.data.user.LogRepositoryImpl
import com.lethio.macros.data.user.UserDatabase
import com.lethio.macros.domain.model.*
import com.lethio.macros.ui.theme.LethioTheme
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Real Compose -> ViewModel -> calculator -> Room flow, with isolated synthetic custom foods. */
class NutritionLogFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock =
        object : TimeProvider {
            override fun today() = LocalDate.of(2026, 10, 7)

            override fun now() = Instant.parse("2026-10-07T12:00:00Z")

            override fun currentTime() = LocalTime.NOON
        }

    @Test
    fun nativeMlCanBeEnteredPreviewedAndSavedWithoutKnownGrams() = exercise(NutritionBasis.VOLUME)

    @Test fun existingMassFlowKeepsItsNumbersAndGrams() = exercise(NutritionBasis.MASS)

    private fun exercise(basis: NutritionBasis) = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
                .setDriver(BundledSQLiteDriver())
                .build()
        val store = ViewModelStore()
        try {
            val food =
                Food(
                    FoodRef.Custom(0),
                    "Synthetic ${basis.code} food",
                    per100g = Macros(40.0, 2.0, 1.0, 3.0),
                    nutritionBasis = basis,
                )
            val id = db.customFoodDao().insert(food.toCustomFoodEntity(clock.now()))
            val provider = FoodDatabaseProvider(context)
            val foods =
                FoodRepositoryImpl(
                    provider,
                    CompoundLexicon(context),
                    TableLanguages(provider),
                    db.customFoodDao(),
                    clock,
                    SettingsManager(context),
                )
            val logs = LogRepositoryImpl(db.dailyLogDao(), db.favoriteDao(), clock)
            lateinit var vm: LogEntryViewModel
            var saved = false
            compose.runOnUiThread {
                vm =
                    LogEntryViewModel(
                        SavedStateHandle(mapOf("foodId" to id, "foodSource" to "custom")),
                        foods,
                        logs,
                        clock,
                        CurrentDiaryDate(clock),
                    )
                store.put("nutrition", vm)
            }
            compose.setContent { LethioTheme { LogEntryScreen({ saved = true }, {}, vm) } }
            compose.waitUntil(5_000) { vm.state.value.load == LogEntryUiState.Load.LOADED }
            compose.onNode(hasSetTextAction()).performTextReplacement("250")
            compose.onNodeWithText("100 kcal").assertExists()
            if (basis == NutritionBasis.VOLUME) compose.onNodeWithText("250 ml").assertExists()
            val image = File(context.cacheDir, "nutrition-${basis.code}-${UUID.randomUUID()}.png")
            image.outputStream().use {
                check(
                    compose
                        .onRoot()
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                )
            }
            InstrumentationRegistry.getInstrumentation()
                .sendStatus(0, Bundle().apply { putString("nutritionUiImage", image.absolutePath) })
            val label = context.getString(R.string.log_food)
            compose
                .onNode(hasText(label) and hasClickAction())
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(5_000) { saved }
            val entry = logs.allEntries().single()
            assertThat(entry.quantity.amount).isEqualTo(250.0)
            assertThat(entry.quantity.basis).isEqualTo(basis)
            assertThat(entry.quantity.basisAmount).isEqualTo(250.0)
            assertThat(entry.quantity.grams)
                .isEqualTo(if (basis == NutritionBasis.VOLUME) null else 250.0)
            assertThat(entry.macros).isEqualTo(Macros(100.0, 5.0, 2.5, 7.5))
            assertThat(logs.observeDay(clock.today()).first().totals).isEqualTo(entry.macros)
        } finally {
            compose.runOnUiThread { store.clear() }
            db.close()
        }
    }
}
