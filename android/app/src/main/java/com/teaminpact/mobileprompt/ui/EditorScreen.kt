package com.teaminpact.mobileprompt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.engine.ScriptStats
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore
import java.text.NumberFormat

/** Edits one script from the library and launches the prompter. Saves on every keystroke. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, store: ScriptStore, settings: AppSettings, onBack: () -> Unit, onStart: (String) -> Unit) {
    val script = store.script(scriptId)
    val text = script?.text ?: ""
    val focus = LocalFocusManager.current
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    var showMarkupHelp by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        script?.titleLine?.ifEmpty { null } ?: stringResource(R.string.script),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { showMarkupHelp = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.markup_help_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            TextField(
                value = text,
                onValueChange = { store.update(scriptId, it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.editor_placeholder)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            // Counts and a time estimate — the pace comes from the reader's last
            // rehearsal once there has been one.
            Text(
                statsLine(ScriptStats.compute(text, settings.paceKo, settings.paceEn)),
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { store.update(scriptId, AppSettings.sampleScript(languageTag)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.insert_sample))
                }
                OutlinedButton(
                    onClick = { store.update(scriptId, "") },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.size(48.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_script), tint = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        focus.clearFocus()
                        onStart(text)
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.start), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (showMarkupHelp) {
        MarkupHelpDialog(onDismiss = { showMarkupHelp = false })
    }
}

/** "1,234자 · 210어절 · 약 1분 20초 (보통 속도)" */
@Composable
private fun statsLine(stats: ScriptStats): String {
    val nf = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val total = Math.round(stats.seconds).toInt()
    val duration = if (total >= 60) stringResource(R.string.duration_min_sec, (total / 60).toString(), (total % 60).toString())
                   else stringResource(R.string.duration_sec, total.toString())
    return stringResource(R.string.stats_line, nf.format(stats.characters), nf.format(stats.words), duration) +
        " " + stringResource(if (stats.measured) R.string.pace_yours else R.string.pace_typical)
}

/** The two bits of markup a script understands (see ScriptMarkup). */
@Composable
private fun MarkupHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.markup_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MarkupRow("**강조**", stringResource(R.string.markup_bold_title), stringResource(R.string.markup_bold_body))
                MarkupRow("[쉬고]", stringResource(R.string.markup_note_title), stringResource(R.string.markup_note_body))
                Text(
                    stringResource(R.string.markup_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun MarkupRow(code: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(0.dp)
                    .then(Modifier),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
