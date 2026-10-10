package com.lethio.macros

import androidx.compose.runtime.mutableFloatStateOf
import com.lethio.macros.ui.util.FitText
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.lethio.macros.ui.navigation.AppNavHost
import com.lethio.macros.ui.settings.countryLabels
import com.lethio.macros.ui.onboarding.ChooseDatabaseScreen
import androidx.compose.runtime.rememberCoroutineScope
import com.lethio.macros.ui.navigation.bottomNavItems
import com.lethio.macros.ui.navigation.Screen
import com.lethio.macros.domain.model.EnergyUnit
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.ui.theme.LethioTheme
import com.lethio.macros.ui.util.LocalEnergyUnit
import com.lethio.macros.ui.util.LocalMassUnit
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsManager: SettingsManager

    override fun attachBaseContext(newBase: Context) {
        // The SharedPreferences mirror: this runs before Hilt, and DataStore would block the main
        // thread. A device that never wrote the mirror falls back to DataStore once.
        val lang = SettingsManager.cachedLanguage(newBase)
            ?: runBlocking {
                newBase.dataStore.data
                    .map { it[SettingsManager.LANGUAGE_KEY] ?: SettingsManager.LANGUAGE_SYSTEM }
                    .first()
            }.also { SettingsManager.cacheLanguage(newBase, it) }
        // Null for "SYSTEM": keep the device locale.
        val locale: Locale? = SettingsManager.localeFor(lang)
        val context = if (locale != null) {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Recreate on a language change. The first emission only refreshes the mirror.
        lifecycleScope.launch {
            var first = true
            settingsManager.languagePreference.collect { lang ->
                // Every emission, so the mirror repairs itself if it drifts.
                SettingsManager.cacheLanguage(this@MainActivity, lang)
                if (first) {
                    first = false
                } else {
                    recreate()
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            // Display preferences are provided at the root rather than through every ViewModel.
            val energyUnit by settingsManager.energyUnit
                .collectAsStateWithLifecycle(initialValue = EnergyUnit.KCAL)

            // Metric until DataStore's first emission, a frame later.
            val massUnit by settingsManager.massUnit
                .collectAsStateWithLifecycle(initialValue = MassUnit.METRIC)

            // The database question comes first: it decides which foods exist and their language.
            val databaseChosen by settingsManager.databaseChosen
                .collectAsStateWithLifecycle(initialValue = null as Boolean?)
            val scope = rememberCoroutineScope()

            CompositionLocalProvider(
                LocalEnergyUnit provides energyUnit,
                LocalMassUnit provides massUnit,
            ) {
            LethioTheme {
                if (databaseChosen == null) {
                    // DataStore still reading: a blank themed frame, not a flash of the app.
                    Surface(modifier = Modifier.fillMaxSize()) {}
                    return@LethioTheme
                }
                if (databaseChosen == false) {
                    // Ask only when locale and SIM name two different covered countries.
                    val localeCountry = remember { SettingsManager.countryFor(SettingsManager.COUNTRY_SYSTEM) }
                    val simCountry = remember { settingsManager.simCountry() }
                    val askCountry = remember(localeCountry, simCountry) {
                        SettingsManager.onboardingAskCountry(localeCountry, simCountry)
                    }
                    if (askCountry == null) {
                        // Nothing ambiguous: mark it answered without storing a country, so SYSTEM
                        // keeps following the device.
                        LaunchedEffect(Unit) { settingsManager.markDatabaseChosen() }
                        Surface(modifier = Modifier.fillMaxSize()) {}
                        return@LethioTheme
                    }
                    ChooseDatabaseScreen(
                        countryLabels = countryLabels(),
                        initialCountry = askCountry,
                        otherCountry = localeCountry,
                        onChosen = { country ->
                            scope.launch {
                                settingsManager.setCountryPreference(country)
                                // As in Settings, choosing a database clears a stored food-language choice.
                                settingsManager.clearFoodLanguages()
                                settingsManager.markDatabaseChosen()
                            }
                        },
                        // Skipping keeps the device's country and is not asked again.
                        onSkip = { scope.launch { settingsManager.markDatabaseChosen() } },
                    )
                    return@LethioTheme
                }
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val historyDiary = currentDestination?.route == Screen.HistoryDay.route
                val showBottomBar = historyDiary || bottomNavItems.any { item ->
                    currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
                }

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            // One label size for all tabs; see FitText.
                            val labelScale = remember { mutableFloatStateOf(1f) }
                            NavigationBar {
                                bottomNavItems.forEach { item ->
                                    val selected = (historyDiary && item.screen == Screen.Diary) || currentDestination?.hierarchy?.any {
                                        it.route == item.screen.route
                                    } == true
                                    val label = stringResource(item.labelRes)
                                    NavigationBarItem(
                                        icon = { Icon(item.icon, contentDescription = label) },
                                        // Shrinks to fit rather than breaking a long label mid-word.
                                        label = { FitText(label, labelScale) },
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(item.screen.tabRoute) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    AppNavHost(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
            }
        }
    }
}
