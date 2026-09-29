package com.lethio.macros

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lethio.macros.domain.model.EnergyUnit
import com.lethio.macros.domain.model.MassUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "macro_tracker_settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val LANGUAGE_KEY = stringPreferencesKey("language_preference")

        private const val LANGUAGE_CACHE_FILE = "language_cache"
        private const val LANGUAGE_CACHE_KEY = "language"

        fun cachedLanguage(context: Context): String? =
            context.getSharedPreferences(LANGUAGE_CACHE_FILE, Context.MODE_PRIVATE)
                .getString(LANGUAGE_CACHE_KEY, null)

        fun cacheLanguage(context: Context, lang: String) {
            context.getSharedPreferences(LANGUAGE_CACHE_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(LANGUAGE_CACHE_KEY, lang)
                .apply()
        }
        val COUNTRY_KEY = stringPreferencesKey("country_preference")
        val ENERGY_UNIT_KEY = stringPreferencesKey("energy_unit")
        val MASS_UNIT_KEY = stringPreferencesKey("mass_unit")
        val OFF_LOOKUP_KEY = stringPreferencesKey("off_lookup_enabled")

        val OFF_CONTRIBUTE_KEY = stringPreferencesKey("off_contribute_enabled")

        val OFF_APP_UUID_KEY = stringPreferencesKey("off_app_uuid")

        val FOOD_LANGUAGES_KEY = stringPreferencesKey("food_language_preference")

        val DATABASE_CHOSEN_KEY = stringPreferencesKey("food_database_chosen")

        const val LANGUAGE_SYSTEM = "SYSTEM"

        const val COUNTRY_SYSTEM = "SYSTEM"

        private val ISO_TO_TAXONOMY_COUNTRY = mapOf("GB" to "UK")

        private val NATIONAL_TABLE_SOURCE = mapOf(
            "US" to "usda", "FR" to "ciqual", "DE" to "bls", "ES" to "bedca",
            "UK" to "cofid",

            "NO" to "matvaretabellen", "SE" to "livsmedel", "FI" to "fineli",
            "DK" to "frida", "CH" to "swiss", "PT" to "tca",
            "CA" to "cnf", "AU" to "afcd",
        )

        fun localeFor(preference: String): Locale? = when (preference) {
            "EN" -> Locale.ENGLISH
            "NB" -> Locale("nb")
            "PT" -> Locale("pt", "PT")
            "FR" -> Locale.FRENCH
            "DE" -> Locale.GERMAN
            "ES" -> Locale("es")
            "SV" -> Locale("sv")
            "IT" -> Locale.ITALIAN
            else -> null
        }

        fun languageFor(preference: String): String =
            (localeFor(preference) ?: Locale.getDefault()).language

        fun countryFor(preference: String): String {
            val code = if (preference == COUNTRY_SYSTEM) {
                Locale.getDefault().country.uppercase()
            } else {
                preference
            }
            return ISO_TO_TAXONOMY_COUNTRY[code] ?: code
        }

        fun defaultMassUnitFor(country: String?): MassUnit = when (country?.uppercase()) {
            "US" -> MassUnit.IMPERIAL_US
            "GB", "UK" -> MassUnit.IMPERIAL_UK
            else -> MassUnit.METRIC
        }

        fun nationalSourceFor(country: String): String = NATIONAL_TABLE_SOURCE[country] ?: ""

        fun onboardingAskCountry(localeCountry: String, simCountry: String?): String? {
            if (simCountry.isNullOrEmpty() || simCountry == localeCountry) return null
            val localeCovered = NATIONAL_TABLE_SOURCE.containsKey(localeCountry)
            val simCovered = NATIONAL_TABLE_SOURCE.containsKey(simCountry)
            return if (localeCovered && simCovered) simCountry else null
        }

        fun foodLanguagesFor(preference: String, appLanguage: String): List<String> {
            val stored = preference.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ordered = stored.ifEmpty { listOf(appLanguage) } + "en"
            return ordered.distinct()
        }

        fun derivedFoodLanguages(
            preference: String,
            appLanguage: String,
            tableLanguages: List<String>,
        ): List<String> {
            val stored = preference.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (stored.isNotEmpty()) return (stored + "en").distinct()
            if (tableLanguages.isEmpty()) return foodLanguagesFor("", appLanguage)
            val readerFirst = tableLanguages.filter { it == appLanguage } + tableLanguages
            return readerFirst.distinct()
        }

        fun foodLanguagePriority(languages: List<String>): String =
            languages.joinToString(separator = ",", prefix = ",", postfix = ",")
    }

    val languagePreference: Flow<String> = context.dataStore.data
        .map { it[LANGUAGE_KEY] ?: "SYSTEM" }
        .distinctUntilChanged()

    suspend fun setLanguagePreference(lang: String) {
        context.dataStore.edit { it[LANGUAGE_KEY] = lang }

        cacheLanguage(context, lang)
    }

    val countryPreference: Flow<String> = context.dataStore.data
        .map { it[COUNTRY_KEY] ?: COUNTRY_SYSTEM }

    suspend fun setCountryPreference(country: String) {
        context.dataStore.edit { it[COUNTRY_KEY] = country }
    }

    fun simCountry(): String? {
        val iso = context.getSystemService(TelephonyManager::class.java)
            ?.simCountryIso
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return ISO_TO_TAXONOMY_COUNTRY[iso] ?: iso
    }

    val foodLanguagePreference: Flow<String> = context.dataStore.data
        .map { it[FOOD_LANGUAGES_KEY] ?: "" }

    suspend fun setFoodLanguages(languages: List<String>) {
        context.dataStore.edit { it[FOOD_LANGUAGES_KEY] = languages.joinToString(",") }
    }

    suspend fun clearFoodLanguages() {
        context.dataStore.edit { it.remove(FOOD_LANGUAGES_KEY) }
    }

    val offLookupEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[OFF_LOOKUP_KEY] == "true" }

    suspend fun setOffLookupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OFF_LOOKUP_KEY] = enabled.toString() }
    }

    val offContributeEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[OFF_CONTRIBUTE_KEY] == "true" }

    suspend fun setOffContributeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OFF_CONTRIBUTE_KEY] = enabled.toString() }
    }

    suspend fun offAppUuid(): String {
        val prefs = context.dataStore.edit { current ->
            if (current[OFF_APP_UUID_KEY].isNullOrBlank()) {
                current[OFF_APP_UUID_KEY] = UUID.randomUUID().toString()
            }
        }
        return prefs[OFF_APP_UUID_KEY].orEmpty()
    }

    val databaseChosen: Flow<Boolean> = context.dataStore.data
        .map { it[DATABASE_CHOSEN_KEY] == "true" }

    suspend fun markDatabaseChosen() {
        context.dataStore.edit { it[DATABASE_CHOSEN_KEY] = "true" }
    }

    val energyUnit: Flow<EnergyUnit> = context.dataStore.data
        .map { EnergyUnit.fromValue(it[ENERGY_UNIT_KEY]) }

    suspend fun setEnergyUnit(unit: EnergyUnit) {
        context.dataStore.edit { it[ENERGY_UNIT_KEY] = unit.value }
    }

    val massUnit: Flow<MassUnit> = context.dataStore.data
        .map { prefs ->
            prefs[MASS_UNIT_KEY]?.let { MassUnit.fromValue(it) }
                ?: defaultMassUnitFor(Locale.getDefault().country)
        }

    suspend fun setMassUnit(unit: MassUnit) {
        context.dataStore.edit { it[MASS_UNIT_KEY] = unit.value }
    }
}
