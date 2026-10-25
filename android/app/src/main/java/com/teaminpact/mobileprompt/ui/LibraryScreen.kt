package com.teaminpact.mobileprompt.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.SavedScript
import com.teaminpact.mobileprompt.model.ScriptStore

/** Script library — the app's home screen. Long-press a row to select several. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(store: ScriptStore, settings: AppSettings, onOpen: (String) -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateOf(setOf<String>()) }

    fun exitSelection() {
        selecting = false
        selection.value = emptySet()
    }
    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    if (selecting) {
                        TextButton(onClick = { exitSelection() }) { Text(stringResource(R.string.done)) }
                    } else {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val n = selection.value.size
                        TextButton(
                            onClick = {
                                store.delete(selection.value)
                                exitSelection()
                            },
                            enabled = n > 0,
                        ) {
                            Text(
                                stringResource(R.string.delete_n, n),
                                color = if (n > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        IconButton(onClick = { onOpen(store.add().id) }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_script))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (store.scripts.isEmpty()) {
            EmptyState(Modifier.padding(padding).fillMaxSize(), onNew = { onOpen(store.add().id) })
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(store.scripts, key = { it.id }) { script ->
                    ScriptRow(
                        script = script,
                        selecting = selecting,
                        selected = script.id in selection.value,
                        onClick = {
                            if (selecting) {
                                selection.value = selection.value.toMutableSet().apply {
                                    if (!add(script.id)) remove(script.id)
                                }
                            } else {
                                onOpen(script.id)
                            }
                        },
                        onLongClick = {
                            if (!selecting) {
                                selecting = true
                                selection.value = setOf(script.id)
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings = settings, compact = false, onDismiss = { showSettings = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptRow(
    script: SavedScript,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                script.titleLine.ifEmpty { stringResource(R.string.new_script) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                DateUtils.formatDateTime(
                    context, script.updatedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onNew: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                Icons.Filled.Description, contentDescription = null,
                Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onNew, modifier = Modifier.widthIn(min = 180.dp).height(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_script))
            }
        }
    }
}
