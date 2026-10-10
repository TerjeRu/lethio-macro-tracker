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

        /**
         * A SharedPreferences mirror of [LANGUAGE_KEY] for `MainActivity.attachBaseContext`, which
         * runs before Hilt and would otherwise block the main thread on DataStore (36 ms vs 2 ms
         * per cold start). DataStore stays the source of truth; write through
         * [setLanguagePreference].
         */
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

        /**
         * Whether the reader allows sending products to Open Food Facts. Independent of
         * [OFF_LOOKUP_KEY]: reading a public database and publishing to it are different decisions.
         */
        val OFF_CONTRIBUTE_KEY = stringPreferencesKey("off_contribute_enabled")

        /**
         * The per-install UUID behind Open Food Facts contributions, so moderators can block one
         * install rather than the shared account. Created on the first contribution; only a salted
         * hash leaves the device, and never with a lookup.
         */
        val OFF_APP_UUID_KEY = stringPreferencesKey("off_app_uuid")

        /**
         * The languages food names are shown in, most preferred first, comma separated. Separate
         * from [LANGUAGE_KEY]: an expat's interface language and the language of the food they buy
         * often differ. Absent means follow the app language.
         */
        val FOOD_LANGUAGES_KEY = stringPreferencesKey("food_language_preference")

        /**
         * Set once the reader has answered the food-database question, including by skipping, so
         * they are not asked again.
         */
        val DATABASE_CHOSEN_KEY = stringPreferencesKey("food_database_chosen")

        /** No stored preference: follow whatever the device is set to. */
        const val LANGUAGE_SYSTEM = "SYSTEM"

        /** No stored preference: follow the device's own country. */
        const val COUNTRY_SYSTEM = "SYSTEM"

        /** The database stores the UK as `UK` (OFF taxonomy), while [Locale] reports `GB`. */
        private val ISO_TO_TAXONOMY_COUNTRY = mapOf("GB" to "UK")

        /**
         * Each country's national table. Mirrors `GENERIC_SOURCE_COUNTRY` in
         * `tools/build_nutrition_db.py`; a missing key silently disables that country's table.
         */
        private val NATIONAL_TABLE_SOURCE = mapOf(
            "US" to "usda", "FR" to "ciqual", "DE" to "bls", "ES" to "bedca",
            "UK" to "cofid",
            "NO" to "matvaretabellen", "SE" to "livsmedel", "FI" to "fineli",
            "DK" to "frida", "CH" to "swiss", "PT" to "tca",
            "CA" to "cnf", "AU" to "afcd",
            "NL" to "nevo", "NZ" to "foodfiles",
        )

        /**
         * Tables whose primary shelf uses the lead-word rule in `FoodDao`: both write long
         * head-first names. Mirrors `LEAD_WORD_SOURCES` in `tools/export_search_results.py`.
         */
        val LEAD_WORD_SOURCES = setOf("nevo", "foodfiles")

        /**
         * The [Locale] a stored preference selects, or null for [LANGUAGE_SYSTEM], which leaves the
         * configuration to the device. These are language codes; the country codes in
         * [NATIONAL_TABLE_SOURCE] are a separate vocabulary.
         */
        fun localeFor(preference: String): Locale? = when (preference) {
            "EN" -> Locale.ENGLISH
            "NB" -> Locale("nb")
            "PT" -> Locale("pt", "PT")
            "FR" -> Locale.FRENCH
            "DE" -> Locale.GERMAN
            "ES" -> Locale("es")
            "SV" -> Locale("sv")
            "IT" -> Locale.ITALIAN
            "NL" -> Locale("nl")
            "DA" -> Locale("da")
            "FI" -> Locale("fi")
            else -> null
        }

        /** The language actually in use, with [LANGUAGE_SYSTEM] resolved to the device default. */
        fun languageFor(preference: String): String =
            (localeFor(preference) ?: Locale.getDefault()).language

        /** The preferred country, with [COUNTRY_SYSTEM] resolved to the device's own. */
        fun countryFor(preference: String): String {
            val code = if (preference == COUNTRY_SYSTEM) {
                Locale.getDefault().country.uppercase()
            } else {
                preference
            }
            return ISO_TO_TAXONOMY_COUNTRY[code] ?: code
        }

        /**
         * The body units a reader in [country] most likely uses. This takes the device locale's
         * country, not the food-database choice: picking the US table says nothing about whether
         * someone weighs themselves in kilograms.
         */
        fun defaultMassUnitFor(country: String?): MassUnit = when (country?.uppercase()) {
            "US" -> MassUnit.IMPERIAL_US
            "GB", "UK" -> MassUnit.IMPERIAL_UK
            else -> MassUnit.METRIC
        }

        fun nationalSourceFor(country: String): String = NATIONAL_TABLE_SOURCE[country] ?: ""

        /**
         * The country to ask about on first run, or null to keep [COUNTRY_SYSTEM] without asking.
         * Asks only when the locale and SIM name two different countries that both have a table,
         * such as an English phone on a Spanish SIM. Both arguments are in taxonomy codes.
         */
        fun onboardingAskCountry(localeCountry: String, simCountry: String?): String? {
            if (simCountry.isNullOrEmpty() || simCountry == localeCountry) return null
            val localeCovered = NATIONAL_TABLE_SOURCE.containsKey(localeCountry)
            val simCovered = NATIONAL_TABLE_SOURCE.containsKey(simCountry)
            return if (localeCovered && simCovered) simCountry else null
        }

        /**
         * Food-name languages from a stored preference, most preferred first, deduplicated. An
         * empty preference means the app language. English is appended because generic rows
         * fall back to their English name.
         */
        fun foodLanguagesFor(preference: String, appLanguage: String): List<String> {
            val stored = preference.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ordered = stored.ifEmpty { listOf(appLanguage) } + "en"
            return ordered.distinct()
        }

        /**
         * Food-name languages derived from the chosen table, unless the reader stored a choice
         * (which changing the database clears).
         *
         * The reader's app language goes first only if the table serves it: a francophone on the
         * Swiss table gets French, a Norwegian does not. English is not appended, because some
         * tables (TCA) have no English names. With no chosen table this falls back to
         * [foodLanguagesFor].
         */
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

        /**
         * The list as `,nb,en,` for `INSTR` ranking in SQL. The outer delimiters stop `en` matching
         * inside `en-GB`.
         */
        fun foodLanguagePriority(languages: List<String>): String =
            languages.joinToString(separator = ",", prefix = ",", postfix = ",")
    }

    /**
     * The interface language. `distinctUntilChanged` is required: DataStore re-emits on every
     * write, and `MainActivity` recreates itself on each emission.
     */
    val languagePreference: Flow<String> = context.dataStore.data
        .map { it[LANGUAGE_KEY] ?: "SYSTEM" }
        .distinctUntilChanged()

    suspend fun setLanguagePreference(lang: String) {
        context.dataStore.edit { it[LANGUAGE_KEY] = lang }
        // DataStore first: a crash in between leaves the mirror stale, and MainActivity repairs it.
        cacheLanguage(context, lang)
    }

    /**
     * The country whose national table is the primary shelf. Other generic sources stay
     * available as fallback, and branded products follow.
     */
    val countryPreference: Flow<String> = context.dataStore.data
        .map { it[COUNTRY_KEY] ?: COUNTRY_SYSTEM }

    suspend fun setCountryPreference(country: String) {
        context.dataStore.edit { it[COUNTRY_KEY] = country }
    }

    /**
     * The SIM's country in taxonomy codes, or null without a SIM. `simCountryIso` needs no
     * permission.
     */
    fun simCountry(): String? {
        val iso = context.getSystemService(TelephonyManager::class.java)
            ?.simCountryIso
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return ISO_TO_TAXONOMY_COUNTRY[iso] ?: iso
    }

    /** See [FOOD_LANGUAGES_KEY]. Empty means follow the app language. */
    val foodLanguagePreference: Flow<String> = context.dataStore.data
        .map { it[FOOD_LANGUAGES_KEY] ?: "" }

    /** Drops a stored food-language choice; called when the reader changes database. */
    suspend fun clearFoodLanguages() {
        context.dataStore.edit { it.remove(FOOD_LANGUAGES_KEY) }
    }

    /**
     * Whether the scanner may offer an Open Food Facts lookup for a barcode the bundled database
     * lacks. Off by default, and even when on the reader taps to look up each barcode. A lookup
     * sends the barcode and the app's User-Agent, nothing else.
     */
    val offLookupEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[OFF_LOOKUP_KEY] == "true" }

    suspend fun setOffLookupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OFF_LOOKUP_KEY] = enabled.toString() }
    }

    /**
     * Whether the reader allows contributing products to Open Food Facts. Off by default and
     * independent of [offLookupEnabled]; each product is approved individually.
     */
    val offContributeEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[OFF_CONTRIBUTE_KEY] == "true" }

    suspend fun setOffContributeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OFF_CONTRIBUTE_KEY] = enabled.toString() }
    }

    /**
     * The install's `app_uuid`, created on first call. `edit` is atomic, so concurrent first calls
     * agree on one value.
     */
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

    /** Energy display unit. kcal by default everywhere; kJ is a choice, not a locale default. */
    val energyUnit: Flow<EnergyUnit> = context.dataStore.data
        .map { EnergyUnit.fromValue(it[ENERGY_UNIT_KEY]) }

    suspend fun setEnergyUnit(unit: EnergyUnit) {
        context.dataStore.edit { it[ENERGY_UNIT_KEY] = unit.value }
    }

    /**
     * Body weight and height units. Unset follows the device region live rather than being written
     * on first launch, so a default is never mistaken for a choice.
     */
    val massUnit: Flow<MassUnit> = context.dataStore.data
        .map { prefs ->
            prefs[MASS_UNIT_KEY]?.let { MassUnit.fromValue(it) }
                ?: defaultMassUnitFor(Locale.getDefault().country)
        }

    suspend fun setMassUnit(unit: MassUnit) {
        context.dataStore.edit { it[MASS_UNIT_KEY] = unit.value }
    }
}
