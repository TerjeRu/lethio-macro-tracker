package com.lethio.macros.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R

@Composable
fun countryLabels(): Map<String, String> = mapOf(
    "SYSTEM" to stringResource(R.string.country_system),
    "US"     to stringResource(R.string.country_us),
    "FR"     to stringResource(R.string.country_fr),
    "DE"     to stringResource(R.string.country_de),
    "ES"     to stringResource(R.string.country_es),
    "UK"     to stringResource(R.string.country_uk),

    "AU"     to stringResource(R.string.country_au),
    "CA"     to stringResource(R.string.country_ca),
    "CH"     to stringResource(R.string.country_ch),
    "DK"     to stringResource(R.string.country_dk),
    "FI"     to stringResource(R.string.country_fi),
    "NO"     to stringResource(R.string.country_no),
    "PT"     to stringResource(R.string.country_pt),
    "SE"     to stringResource(R.string.country_se),
)
