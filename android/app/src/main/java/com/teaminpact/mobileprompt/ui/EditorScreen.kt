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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore

/** Edits one script from the library and launches the prompter. Saves on every keystroke. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, store: ScriptStore, onBack: () -> Unit, onStart: (String) -> Unit) {
    val script = store.script(scriptId)
    val text = script?.text ?: ""
    val focus = LocalFocusManager.current
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()

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
}
