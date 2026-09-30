package com.lethio.macros.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lethio.macros.R
import com.lethio.macros.domain.model.DataSourceAttribution

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionScreen(onNavigateBack: () -> Unit, onSoftwareLicenses: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_sources)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.data_sources_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            TextButton(onClick = onSoftwareLicenses) {
                Text(stringResource(R.string.software_licenses))
            }
            HorizontalDivider()

            DataSourceAttribution.ALL.forEach { attribution ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (attribution.url != null) {
                                Modifier.clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, attribution.url.toUri()),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(attribution.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        attribution.licence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (attribution.required != null) {
                        Text(
                            attribution.required,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    listOfNotNull(attribution.licenceUrl, attribution.downloadUrl).forEach { url ->
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }) {
                            Text(url)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
