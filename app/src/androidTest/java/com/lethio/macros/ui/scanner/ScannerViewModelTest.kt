package com.lethio.macros.ui.scanner

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.off.OffLookupService
import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.repository.ActiveDatabase
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.ui.product.AddProductUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's lookup must always reach a terminal state, because the re-entrancy guard latches on
 * `RUNNING`. That includes a throw from `saveCustomFood` (a full disk, a constraint violation).
 *
 * Instrumented because `ScannerViewModel` needs a DataStore-backed `SettingsManager` and a real
 * `OffLookupService`; the lookup is faked at the wire.
 */
@RunWith(AndroidJUnit4::class)
class ScannerViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A real Open Food Facts v3 payload, trimmed to the fields the mapper reads. */
    private val foundBody =
        """
        {
          "code": "5012345678900",
          "status": "success",
          "result": { "id": "product_found" },
          "product": {
            "product_name": "Test Porridge Oats",
            "brands": "TestBrand",
            "quantity": "500 g",
            "nutriments": {
              "energy-kcal_100g": 303.0,
              "proteins_100g": 11.0,
              "fat_100g": 6.2,
              "carbohydrates_100g": 50.0
            }
          }
        }
    """
            .trimIndent()

    private fun lookupServiceReturning(body: String, code: Int = 200, timeout: Boolean = false) =
        OffLookupService(
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        if (timeout) throw java.net.SocketTimeoutException("test timeout")
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("OK")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                )
                .build(),
            Json { ignoreUnknownKeys = true },
        )

    /** Fails the way a full disk does: after the lookup succeeded, on the way to storage. */
    private class ThrowingRepository(
        private val onSave: () -> Nothing = { throw IllegalStateException("disk full") }
    ) : FoodRepository {
        var saveAttempted = false

        override suspend fun saveCustomFood(food: Food): FoodRef {
            saveAttempted = true
            onSave()
        }

        override suspend fun search(query: String, limit: Int): List<Food> = emptyList()

        override suspend fun findByBarcode(barcode: String): Food? = null

        override suspend fun getByRef(ref: FoodRef): Food? = null

        override suspend fun deleteCustomFood(ref: FoodRef.Custom) = Unit

        override suspend fun activeDatabase(): ActiveDatabase? = null
    }

    private fun viewModel(repository: FoodRepository) =
        ScannerViewModel(repository, lookupServiceReturning(foundBody), SettingsManager(context))

    @Test
    fun aFailedSaveLeavesTheScannerRetryable() =
        runBlocking(Dispatchers.Main) {
            val repository = ThrowingRepository()
            val model = viewModel(repository)

            model.lookUpOnline("5012345678900") {
                error("onFound must not run when the save failed")
            }
            awaitSettled(model)

            assertThat(repository.saveAttempted).isTrue()
            // UNAVAILABLE, not RUNNING. The enum's own KDoc says it is the retryable one, which is
            // the
            // truthful thing to tell someone whose lookup worked and whose save did not.
            assertThat(model.lookupState.value).isEqualTo(LookupState.UNAVAILABLE)
        }

    @Test
    fun aFailedSaveDoesNotLatchTheReentrancyGuard() =
        runBlocking(Dispatchers.Main) {
            // The part that actually stranded the screen: with the state stuck at RUNNING, the
            // early
            // return at the top of lookUpOnline made every later attempt a no-op.
            val repository = ThrowingRepository()
            val model = viewModel(repository)

            model.lookUpOnline("5012345678900") {}
            awaitSettled(model)
            repository.saveAttempted = false

            model.lookUpOnline("5012345678900") {}
            awaitSettled(model)

            assertThat(repository.saveAttempted).isTrue()
        }

    @Test
    fun aSuccessfulSaveStillReportsTheFoodAndGoesIdle() =
        runBlocking(Dispatchers.Main) {
            // The guard must not have swallowed the happy path.
            var reported: FoodRef? = null
            val model =
                viewModel(
                    object : FoodRepository by ThrowingRepository() {
                        override suspend fun saveCustomFood(food: Food) = FoodRef.Custom(42)
                    }
                )

            model.lookUpOnline("5012345678900") { reported = it }
            awaitSettled(model)

            assertThat(reported).isEqualTo(FoodRef.Custom(42))
            assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
        }

    @Test
    fun onlyConfirmedAbsenceEnablesContributionAndEveryFailureAllowsLocalSaving() =
        runBlocking(Dispatchers.Main) {
            data class Case(
                val body: String,
                val code: Int = 200,
                val barcode: String = "5012345678900",
                val timeout: Boolean = false,
                val expected: LookupState,
            )
            val cases =
                listOf(
                    Case("", code = 404, expected = LookupState.NOTHING_FOUND),
                    Case(
                        """{"result":{"id":"product_not_found"}}""",
                        expected = LookupState.NOTHING_FOUND,
                    ),
                    Case(
                        """{"result":{"id":"product_found"},"product":{"product_name":"Water","nutriments":{"energy-kcal_100g":0}}}""",
                        expected = LookupState.NOT_USABLE,
                    ),
                    Case(
                        foundBody.replace(
                            "\"quantity\":",
                            "\"data_quality_errors_tags\":[\"en:energy-value-error\"],\"quantity\":",
                        ),
                        expected = LookupState.NOT_USABLE,
                    ),
                    Case("bad json", expected = LookupState.UNAVAILABLE),
                    Case("{}", timeout = true, expected = LookupState.UNAVAILABLE),
                    Case("{}", barcode = "123", expected = LookupState.INVALID_BARCODE),
                )
            for (case in cases) {
                val repo = ThrowingRepository()
                val model =
                    ScannerViewModel(
                        repo,
                        lookupServiceReturning(case.body, case.code, case.timeout),
                        SettingsManager(context),
                    )
                model.lookUpOnline(case.barcode) {
                    error("Rejected result must not navigate to a food")
                }
                awaitSettled(model)
                assertThat(model.lookupState.value).isEqualTo(case.expected)
                assertThat(repo.saveAttempted).isFalse()
                val manual =
                    AddProductUiState(
                        barcode = case.barcode,
                        name = "Manual product",
                        calories = "40",
                        protein = "0",
                        fat = "0",
                        carbs = "10",
                        offConfirmedMissing = model.lookupState.value.offConfirmedMissing,
                    )
                assertThat(manual.canSave).isTrue()
                assertThat(manual.canContribute)
                    .isEqualTo(case.expected == LookupState.NOTHING_FOUND)
                model.resetLookup()
                assertThat(model.lookupState.value.offConfirmedMissing).isFalse()
            }
        }

    @Test
    fun zeroEnergyLookupSavesItsOriginalNutritionAndNavigates() =
        runBlocking(Dispatchers.Main) {
            var saved: Food? = null
            var reported: FoodRef? = null
            val repository =
                object : FoodRepository by ThrowingRepository() {
                    override suspend fun saveCustomFood(food: Food): FoodRef {
                        saved = food
                        return FoodRef.Custom(43)
                    }
                }
            val body =
                """{"result":{"id":"product_found"},"product":{"product_name":"Mineral water","nutriments":{"energy-kcal_100g":0,"proteins_100g":0,"fat_100g":0,"carbohydrates_100g":0}}}"""
            val model =
                ScannerViewModel(repository, lookupServiceReturning(body), SettingsManager(context))
            model.lookUpOnline("5012345678900") { reported = it }
            awaitSettled(model)
            assertThat(saved!!.per100g.calories).isEqualTo(0.0)
            assertThat(saved!!.barcode).isEqualTo("5012345678900")
            assertThat(reported).isEqualTo(FoodRef.Custom(43))
            assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
            assertThat(model.lookupState.value.offConfirmedMissing).isFalse()
        }

    @Test
    fun cancellationDuringSavingPropagatesWithoutAnUnavailableState() =
        runBlocking(Dispatchers.Main) {
            val enteredSave = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            val repository =
                object : FoodRepository by ThrowingRepository() {
                    override suspend fun saveCustomFood(food: Food): FoodRef {
                        enteredSave.complete(Unit)
                        releaseSave.await()
                        throw CancellationException("leaving scanner")
                    }
                }
            val model = viewModel(repository)
            var callbacks = 0
            val existingJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.lookUpOnline("5012345678900") { callbacks++ }
            withTimeout(5_000) { enteredSave.await() }
            val completed = CompletableDeferred<Throwable?>()
            model.viewModelScope.coroutineContext[Job]!!
                .children
                .single { it !in existingJobs }
                .invokeOnCompletion { completed.complete(it) }
            releaseSave.complete(Unit)
            assertThat(withTimeout(5_000) { completed.await() })
                .isInstanceOf(CancellationException::class.java)
            assertThat(model.lookupState.value).isEqualTo(LookupState.RUNNING)
            assertThat(callbacks).isEqualTo(0)
            model.viewModelScope.coroutineContext[Job]!!.cancel()
        }

    @Test
    fun navigationFailureEscapesWithoutRelabelingOrRepeatingTheSave() =
        runBlocking(Dispatchers.Main) {
            var saves = 0
            var callbacks = 0
            val model =
                viewModel(
                    object : FoodRepository by ThrowingRepository() {
                        override suspend fun saveCustomFood(food: Food): FoodRef {
                            saves++
                            return FoodRef.Custom(45)
                        }
                    }
                )
            val failure = IllegalStateException("navigation failed after saving")
            val escaped = CompletableDeferred<Throwable>()
            val thread = Thread.currentThread()
            val originalHandler = thread.uncaughtExceptionHandler
            // Observe the callback's escaping exception without terminating the instrumentation
            // process. Unexpected exceptions still reach the original handler.
            thread.uncaughtExceptionHandler =
                Thread.UncaughtExceptionHandler { current, error ->
                    if (error === failure) escaped.complete(error)
                    else originalHandler?.uncaughtException(current, error)
                }
            try {
                model.lookUpOnline("5012345678900") {
                    callbacks++
                    assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
                    throw failure
                }
                assertThat(withTimeout(5_000) { escaped.await() }).isSameInstanceAs(failure)
                assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
                assertThat(saves).isEqualTo(1)
                assertThat(callbacks).isEqualTo(1)
            } finally {
                thread.uncaughtExceptionHandler = originalHandler
                model.viewModelScope.coroutineContext[Job]!!.cancel()
            }
        }

    @Test
    fun navigationCancellationDoesNotRelabelASuccessfulSave() =
        runBlocking(Dispatchers.Main) {
            var saves = 0
            var callbacks = 0
            val model =
                viewModel(
                    object : FoodRepository by ThrowingRepository() {
                        override suspend fun saveCustomFood(food: Food): FoodRef {
                            saves++
                            return FoodRef.Custom(44)
                        }
                    }
                )
            val callbackReached = CompletableDeferred<Unit>()
            model.lookUpOnline("5012345678900") {
                callbacks++
                assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
                callbackReached.complete(Unit)
                throw CancellationException("navigation cancelled")
            }
            withTimeout(5_000) { callbackReached.await() }
            // Let the launched coroutine finish, so the old catch-all has time to overwrite IDLE.
            delay(20)
            assertThat(model.lookupState.value).isEqualTo(LookupState.IDLE)
            assertThat(saves).isEqualTo(1)
            assertThat(callbacks).isEqualTo(1)
            model.viewModelScope.coroutineContext[Job]!!.cancel()
        }

    private suspend fun awaitSettled(model: ScannerViewModel) {
        repeat(100) {
            if (model.lookupState.value != LookupState.RUNNING) return
            delay(20)
        }
    }
}
