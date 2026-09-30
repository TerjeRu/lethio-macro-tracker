package com.lethio.macros.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lethio.macros.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class SoftwareNotice(val id: String, val name: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoftwareLicensesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState(emptyList<SoftwareNotice>(), context) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("software-licenses.json").bufferedReader().use { it.readText() }
            val rows = JSONObject(json).getJSONArray("notices")
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                SoftwareNotice(row.getString("id"), row.getString("name"), row.getString("text"))
            }
        }
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.software_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_cd))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(notices, key = { it.id }) { notice ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TextButton(
                        onClick = { expanded = if (expanded == notice.id) null else notice.id },
                    ) {
                        Text(notice.name)
                    }
                    if (expanded == notice.id) {
                        SelectionContainer {
                            Text(
                                notice.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
