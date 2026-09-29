package com.lethio.macros.ui.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lethio.macros.BuildConfig
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lethio.macros.SettingsManager
import com.lethio.macros.data.food.TableLanguages
import com.lethio.macros.data.off.OffContributeService
import com.lethio.macros.domain.model.EnergyUnit
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.repository.FoodRepository
import com.lethio.macros.domain.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val foodRepository: FoodRepository,
    private val settingsManager: SettingsManager,
    private val tableLanguages: TableLanguages,
    offContributeService: OffContributeService,
) : ViewModel() {

    val languagePreference: StateFlow<String> = settingsManager.languagePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SYSTEM")

    fun setLanguagePreference(lang: String) {
        viewModelScope.launch { settingsManager.setLanguagePreference(lang) }
    }

    val foodLanguages: StateFlow<List<String>> = settingsManager.foodLanguagePreference
        .map { pref -> pref.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _availableNameLanguages = MutableStateFlow<List<String>>(emptyList())
    val availableNameLanguages: StateFlow<List<String>> = _availableNameLanguages.asStateFlow()

    init {
        viewModelScope.launch {
            _availableNameLanguages.value =
                runCatching { foodRepository.availableNameLanguages() }.getOrDefault(emptyList())
        }
    }

    val countryPreference: StateFlow<String> = settingsManager.countryPreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SYSTEM")

    fun setCountryPreference(country: String) {
        viewModelScope.launch {
            settingsManager.setCountryPreference(country)
            settingsManager.clearFoodLanguages()
        }
    }

    val databaseLanguages: StateFlow<List<String>> = combine(
        settingsManager.countryPreference,
        settingsManager.foodLanguagePreference,
        settingsManager.languagePreference,
    ) { country, stored, appLanguage ->
        val source = SettingsManager.nationalSourceFor(SettingsManager.countryFor(country))
        SettingsManager.derivedFoodLanguages(
            stored,
            SettingsManager.languageFor(appLanguage),
            tableLanguages.forSource(source),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val energyUnit: StateFlow<EnergyUnit> = settingsManager.energyUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUnit.KCAL)

    val offLookupEnabled: StateFlow<Boolean> = settingsManager.offLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setOffLookupEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setOffLookupEnabled(enabled) }
    }

    val offContributeEnabled: StateFlow<Boolean> = settingsManager.offContributeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val offContributeConfigured: Boolean = offContributeService.isConfigured

    fun setOffContributeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setOffContributeEnabled(enabled) }
    }

    val massUnit: StateFlow<MassUnit> = settingsManager.massUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MassUnit.METRIC)

    fun setMassUnit(unit: MassUnit) {
        viewModelScope.launch { settingsManager.setMassUnit(unit) }
    }

    fun toggleEnergyUnit() {
        viewModelScope.launch {
            settingsManager.setEnergyUnit(
                if (energyUnit.value == EnergyUnit.KCAL) EnergyUnit.KJ else EnergyUnit.KCAL,
            )
        }
    }

    fun exportCsv(context: Context, onResult: (ExportResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { writeCsv(context) }
            onResult(result)
        }
    }

    private suspend fun writeCsv(context: Context): ExportResult {
        val entries = logRepository.allEntries()
        if (entries.isEmpty()) return ExportResult.NothingToExport

        return runCatching {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, EXPORT_FILE)
            file.bufferedWriter().use { writer ->
                writer.appendLine(HEADER)
                entries.forEach { writer.appendLine(it.toCsvRow()) }
            }
            ExportResult.Success(
                uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                ),
                entryCount = entries.size,
            )
        }.getOrElse {
            if (BuildConfig.DEBUG) Log.w("SettingsViewModel", "CSV export failed", it)
            ExportResult.Failed
        }
    }

    sealed interface ExportResult {
        data class Success(val uri: android.net.Uri, val entryCount: Int) : ExportResult
        data object NothingToExport : ExportResult

        data object Failed : ExportResult
    }

    companion object {
        private const val EXPORT_DIR = "exports"
        private const val EXPORT_FILE = "lethio-diary.csv"

        internal const val HEADER =
            "date,meal,food_name,brand,quantity,unit,grams,calories,protein_g,fat_g,carbs_g"

        fun shareIntent(uri: android.net.Uri): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}

internal fun String?.csvEscape(): String {
    val value = this ?: return ""
    val guarded = if (value.firstOrNull() in FORMULA_TRIGGERS) "'$value" else value
    return "\"" + guarded.replace("\"", "\"\"") + "\""
}

private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

internal fun LogEntry.toCsvRow(): String = listOf(
    date.toString(),
    meal.name.lowercase(),
    foodName.csvEscape(),
    brand.csvEscape(),

    quantity.amount.toString(),
    quantity.unit.label.csvEscape(),
    quantity.grams.toString(),
    macros.calories.toString(),
    macros.proteinG.toString(),
    macros.fatG.toString(),
    macros.carbsG.toString(),
).joinToString(",")
