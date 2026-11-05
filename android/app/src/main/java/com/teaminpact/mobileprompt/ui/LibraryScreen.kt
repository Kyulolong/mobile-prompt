package com.teaminpact.mobileprompt.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.SavedScript
import com.teaminpact.mobileprompt.model.ScriptStore
import com.teaminpact.mobileprompt.model.TextDecoding

/**
 * Script library — the app's home screen. Swipe a row left to delete it;
 * long-press for "duplicate" / "select multiple" / "delete" (the same menu as
 * on iOS). "+" makes a new script; its menu also imports a text file.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(store: ScriptStore, settings: AppSettings, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateOf(setOf<String>()) }
    var addMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    fun exitSelection() {
        selecting = false
        selection.value = emptySet()
    }
    fun exitSearch() {
        searching = false
        query = ""
    }
    BackHandler(enabled = selecting || searching) { if (selecting) exitSelection() else exitSearch() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }

    // A picked .txt/.md becomes a new script and opens in the editor.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = TextDecoding.read(context.contentResolver, uri)
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
        } else {
            onOpen(store.add(text).id)
        }
    }

    val visible = remember(store.scripts.toList(), query) {
        val q = query.trim()
        if (q.isEmpty()) store.scripts.toList() else store.scripts.filter { it.text.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            placeholder = { Text(stringResource(R.string.search_scripts)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (selecting) {
                        TextButton(onClick = { exitSelection() }) { Text(stringResource(R.string.done)) }
                    } else if (searching) {
                        IconButton(onClick = { exitSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        }
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
                        if (!searching && store.scripts.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_scripts))
                            }
                        }
                        Box {
                            IconButton(onClick = { addMenu = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_script))
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.new_script)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = { addMenu = false; onOpen(store.add().id) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_text_file)) },
                                    leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                                    onClick = { addMenu = false; importLauncher.launch(arrayOf("text/*")) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (store.scripts.isEmpty()) {
            EmptyState(
                Modifier.padding(padding).fillMaxSize(),
                onNew = { onOpen(store.add().id) },
                onImport = { importLauncher.launch(arrayOf("text/*")) },
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(visible, key = { it.id }) { script ->
                    SwipeToDismissBox(
                        state = rememberSwipeToDismissBoxState(),
                        onDismiss = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) store.delete(setOf(script.id))
                        },
                        enableDismissFromStartToEnd = false,
                        gesturesEnabled = !selecting,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        },
                    ) {
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
                            onDuplicate = { store.duplicate(script.id) },
                            onSelectMultiple = {
                                selecting = true
                                selection.value = setOf(script.id)
                            },
                            onDelete = { store.delete(setOf(script.id)) },
                        )
                    }
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
    onDuplicate: () -> Unit,
    onSelectMultiple: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { if (!selecting) menuExpanded = true })
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
    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.duplicate)) },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = { menuExpanded = false; onDuplicate() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.select_multiple)) },
            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
            onClick = { menuExpanded = false; onSelectMultiple() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { menuExpanded = false; onDelete() },
        )
    }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onNew: () -> Unit, onImport: () -> Unit) {
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
            TextButton(onClick = onImport) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_text_file))
            }
        }
    }
}
