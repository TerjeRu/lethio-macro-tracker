package com.lethio.macros.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lethio.macros.BuildConfig
import com.lethio.macros.R
import com.lethio.macros.SettingsManager
import com.lethio.macros.domain.model.EnergyUnit
import com.lethio.macros.domain.model.MassUnit
import com.lethio.macros.ui.util.labelRes
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToAttribution: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedLang by viewModel.languagePreference.collectAsStateWithLifecycle()
    val selectedCountry by viewModel.countryPreference.collectAsStateWithLifecycle()
    val databaseLanguages by viewModel.databaseLanguages.collectAsStateWithLifecycle()
    val energyUnit by viewModel.energyUnit.collectAsStateWithLifecycle()
    val massUnit by viewModel.massUnit.collectAsStateWithLifecycle()
    val offLookupEnabled by viewModel.offLookupEnabled.collectAsStateWithLifecycle()
    val offContributeEnabled by viewModel.offContributeEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportChooserTitle = stringResource(R.string.export_csv)
    val nothingToExport = stringResource(R.string.export_nothing)
    val exportFailed = stringResource(R.string.export_failed)

    val languageLabels = mapOf(
        "SYSTEM" to stringResource(R.string.language_system),
        "EN"     to stringResource(R.string.language_en),
        "NB"     to stringResource(R.string.language_nb),
        "PT"     to stringResource(R.string.language_pt),
        "FR"     to stringResource(R.string.language_fr),
        "DE"     to stringResource(R.string.language_de),
        "ES"     to stringResource(R.string.language_es),
        "SV"     to stringResource(R.string.language_sv),
        "IT"     to stringResource(R.string.language_it),
        "NL"     to stringResource(R.string.language_nl),
        "DA"     to stringResource(R.string.language_da),
        "FI"     to stringResource(R.string.language_fi),
    )

    val countryLabels = countryLabels()
    // Shown on its own line: the dropdown would truncate a long country name.
    val resolvedSystemCountry = if (selectedCountry == SettingsManager.COUNTRY_SYSTEM) {
        // Named in the app's language, not the phone's.
        SettingsManager.countryFor(SettingsManager.COUNTRY_SYSTEM).let { code ->
            countryLabels[code]
                ?: Locale("", code).getDisplayCountry(LocalConfiguration.current.locales[0])
        }
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Scrollable: at large text sizes the rows outgrow the screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            LanguagePickerItem(
                selectedLang = selectedLang,
                languageLabels = languageLabels,
                onLanguageSelected = viewModel::setLanguagePreference,
            )
            HorizontalDivider()

            CountryPickerItem(
                selectedCountry = selectedCountry,
                countryLabels = countryLabels,
                onCountrySelected = viewModel::setCountryPreference,
            )
            Text(
                text = stringResource(R.string.generic_database_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            if (resolvedSystemCountry != null) {
                Text(
                    text = stringResource(R.string.country_system_resolved, resolvedSystemCountry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
            }
            // States which name languages the database choice implies.
            if (databaseLanguages.isNotEmpty()) {
                // Named in the app's language: the JVM default locale ignores the in-app override.
                val appLocale = Locale(SettingsManager.languageFor(selectedLang))
                Text(
                    text = stringResource(
                        R.string.database_language,
                        // In the reader's language, not endonyms: this is a sentence, not a picker.
                        databaseLanguages.joinToString(", ") { tag ->
                            Locale.forLanguageTag(tag).getDisplayLanguage(appLocale).ifEmpty { tag }
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
            }

            HorizontalDivider()

            MassUnitPickerItem(
                selected = massUnit,
                onSelected = viewModel::setMassUnit,
            )
            HorizontalDivider()

            SettingsItem(
                title = stringResource(R.string.energy_unit_title),
                subtitle = stringResource(
                    if (energyUnit == EnergyUnit.KCAL) {
                        R.string.energy_unit_kcal
                    } else {
                        R.string.energy_unit_kj
                    },
                ),
                onClick = viewModel::toggleEnergyUnit,
            )
            HorizontalDivider()

            // The scanner still asks per barcode; see ScannerScreen.
            SettingsItem(
                title = stringResource(R.string.settings_off_lookup),
                subtitle = stringResource(R.string.settings_off_lookup_summary),
                onClick = { viewModel.setOffLookupEnabled(!offLookupEnabled) },
                checked = offLookupEnabled,
                trailing = {
                    Switch(
                        checked = offLookupEnabled,
                        onCheckedChange = null,
                    )
                },
            )
            HorizontalDivider()

            // Independent of the lookup switch. Hidden in builds without a relay URL.
            if (viewModel.offContributeConfigured) {
                SettingsItem(
                    title = stringResource(R.string.settings_off_contribute),
                    subtitle = stringResource(R.string.settings_off_contribute_summary),
                    onClick = { viewModel.setOffContributeEnabled(!offContributeEnabled) },
                    checked = offContributeEnabled,
                    trailing = {
                        Switch(
                            checked = offContributeEnabled,
                            onCheckedChange = null,
                        )
                    },
                )
                HorizontalDivider()
            }

            SettingsItem(
                title = stringResource(R.string.export_csv),
                subtitle = stringResource(R.string.export_csv_subtitle),
                opensExternally = true,
                onClick = {
                    viewModel.exportCsv(context) { result ->
                        when (result) {
                            is SettingsViewModel.ExportResult.Success ->
                                context.startActivity(
                                    Intent.createChooser(
                                        SettingsViewModel.shareIntent(result.uri),
                                        exportChooserTitle,
                                    ),
                                )

                            SettingsViewModel.ExportResult.NothingToExport ->
                                scope.launch { snackbarHostState.showSnackbar(nothingToExport) }

                            SettingsViewModel.ExportResult.Failed ->
                                scope.launch { snackbarHostState.showSnackbar(exportFailed) }
                        }
                    }
                },
            )
            HorizontalDivider()
            // Several datasets are licensed on condition that they are credited.
            SettingsItem(
                title = stringResource(R.string.data_sources),
                subtitle = stringResource(R.string.data_sources_subtitle),
                onClick = onNavigateToAttribution,
            )
            HorizontalDivider()
            SettingsItem(
                title = stringResource(R.string.about),
                // Name and version from the manifest and BuildConfig, not translated strings.
                subtitle = stringResource(
                    R.string.about_subtitle,
                    stringResource(R.string.app_name),
                    BuildConfig.VERSION_NAME,
                ),
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerItem(
    selectedLang: String,
    languageLabels: Map<String, String>,
    onLanguageSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = languageLabels[selectedLang] ?: selectedLang

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The weight and fillMaxWidth are both needed: OutlinedTextField's 280.dp minimum width
        // otherwise squeezes the label to a few characters per line.
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1.6f),
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                languageLabels.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onLanguageSelected(code)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

/**
 * The body-units picker: a menu from a plain row rather than an `OutlinedTextField`, which would
 * truncate labels like "Pounds, feet and inches (US)".
 */
@Composable
private fun MassUnitPickerItem(
    selected: MassUnit,
    onSelected: (MassUnit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsItem(
            title = stringResource(R.string.mass_unit_title),
            subtitle = stringResource(selected.labelRes),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MassUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(stringResource(unit.labelRes)) },
                    onClick = {
                        onSelected(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerItem(
    selectedCountry: String,
    countryLabels: Map<String, String>,
    onCountrySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = countryLabels[selectedCountry] ?: selectedCountry

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.generic_food_database),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The weight and fillMaxWidth are both needed: OutlinedTextField's 280.dp minimum width
        // otherwise squeezes the label to a few characters per line.
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1.6f),
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                countryLabels.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onCountrySelected(code)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    /**
     * Whether the row hands off to another app. The open-in-new glyph means exactly that, so
     * showing it on an in-app toggle tells the user something untrue.
     */
    opensExternally: Boolean = false,
    /** Trailing control, for rows that are a switch rather than a destination. */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Set for a switch row. The whole row is then one toggle with the switch role, and the
     * [trailing] Switch must be display-only (onCheckedChange = null): otherwise TalkBack stops
     * twice, the second time on a switch with no name.
     */
    checked: Boolean? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick == null -> Modifier
                    checked != null -> Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (opensExternally) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}
