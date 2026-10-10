package com.lethio.macros.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.off.OffLookupResult
import com.lethio.macros.data.off.OffLookupService
import com.lethio.macros.domain.model.FoodRef
import com.lethio.macros.domain.repository.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the barcode-not-found screen is currently doing about an online lookup. */
enum class LookupState {
    /** No lookup attempted, or the setting is off. */
    IDLE,

    /** A request is in flight. */
    RUNNING,

    /** OFF explicitly reports this barcode missing. */
    NOTHING_FOUND,

    /** An existing product failed validation; manual saving is still available. */
    NOT_USABLE,

    /** No request was made because the barcode failed local validation. */
    INVALID_BARCODE,

    /** Offline, timed out, or refused. Distinct from [NOTHING_FOUND] because it is worth retrying. */
    UNAVAILABLE;

    val offConfirmedMissing: Boolean get() = this == NOTHING_FOUND
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val offLookupService: OffLookupService,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    /** Whether Open Food Facts lookups are allowed. Off by default. */
    val offLookupEnabled: StateFlow<Boolean> = settingsManager.offLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lookupState = MutableStateFlow(LookupState.IDLE)
    val lookupState: StateFlow<LookupState> = _lookupState.asStateFlow()

    /** Resolves a barcode locally: custom foods, then the bundled database. Never uses the network. */
    fun lookupBarcode(barcode: String, onResult: (FoodRef?) -> Unit) {
        viewModelScope.launch {
            onResult(foodRepository.findByBarcode(barcode)?.ref)
        }
    }

    /**
     * Asks Open Food Facts about a barcode the local databases lacked, from a button the reader
     * taps. A usable result is saved as a custom food with its provenance, so the next scan works
     * offline and log entry handles portions as usual.
     */
    fun lookUpOnline(barcode: String, onFound: (FoodRef) -> Unit) {
        // Safe outside the coroutine: viewModelScope is Main.immediate, so two taps cannot interleave.
        if (_lookupState.value == LookupState.RUNNING) return
        viewModelScope.launch {
            _lookupState.value = LookupState.RUNNING
            // Every outcome reaches a terminal state, including a failed save, so the RUNNING guard
            // cannot latch. Cancellation propagates.
            var found: FoodRef? = null
            try {
                when (val result = offLookupService.lookup(barcode)) {
                    is OffLookupResult.Found -> {
                        found = foodRepository.saveCustomFood(result.food)
                        _lookupState.value = LookupState.IDLE
                    }

                    OffLookupResult.Missing -> _lookupState.value = LookupState.NOTHING_FOUND
                    OffLookupResult.NotUsable -> _lookupState.value = LookupState.NOT_USABLE
                    OffLookupResult.InvalidBarcode -> _lookupState.value = LookupState.INVALID_BARCODE
                    OffLookupResult.Unavailable -> _lookupState.value = LookupState.UNAVAILABLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _lookupState.value = LookupState.UNAVAILABLE
                return@launch
            }
            // Navigation failures happen after a successful save and must not relabel it as a
            // failed lookup. Cancellation belongs to the coroutine, not a retryable outcome.
            found?.let(onFound)
        }
    }

    /** Turns the lookup on from the scanner, after the reader has seen what it sends. */
    fun enableOffLookup() {
        viewModelScope.launch { settingsManager.setOffLookupEnabled(true) }
    }

    /** Called when the user scans again, so a previous failure does not describe a new barcode. */
    fun resetLookup() {
        _lookupState.value = LookupState.IDLE
    }
}
