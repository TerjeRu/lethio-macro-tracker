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

enum class LookupState {

    IDLE,

    RUNNING,

    NOTHING_FOUND,

    NOT_USABLE,

    INVALID_BARCODE,

    UNAVAILABLE;

    val offConfirmedMissing: Boolean get() = this == NOTHING_FOUND
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val offLookupService: OffLookupService,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    val offLookupEnabled: StateFlow<Boolean> = settingsManager.offLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lookupState = MutableStateFlow(LookupState.IDLE)
    val lookupState: StateFlow<LookupState> = _lookupState.asStateFlow()

    fun lookupBarcode(barcode: String, onResult: (FoodRef?) -> Unit) {
        viewModelScope.launch {
            onResult(foodRepository.findByBarcode(barcode)?.ref)
        }
    }

    fun lookUpOnline(barcode: String, onFound: (FoodRef) -> Unit) {

        if (_lookupState.value == LookupState.RUNNING) return
        viewModelScope.launch {
            _lookupState.value = LookupState.RUNNING

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

            found?.let(onFound)
        }
    }

    fun enableOffLookup() {
        viewModelScope.launch { settingsManager.setOffLookupEnabled(true) }
    }

    fun resetLookup() {
        _lookupState.value = LookupState.IDLE
    }
}
