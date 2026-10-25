package com.teaminpact.mobileprompt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore

/** Three screens, one stack: library → editor → prompter. */
sealed class Screen {
    object Library : Screen()
    data class Editor(val scriptId: String) : Screen()
    data class Prompter(val scriptId: String, val script: String) : Screen()
}

@Composable
fun AppRoot(settings: AppSettings, store: ScriptStore) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Library) }

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            store = store,
            settings = settings,
            onOpen = { id -> screen = Screen.Editor(id) },
        )
        is Screen.Editor -> {
            BackHandler { screen = Screen.Library }
            EditorScreen(
                scriptId = s.scriptId,
                store = store,
                onBack = { screen = Screen.Library },
                onStart = { text -> screen = Screen.Prompter(s.scriptId, text) },
            )
        }
        is Screen.Prompter -> PrompterScreen(
            script = s.script,
            settings = settings,
            onClose = { screen = Screen.Editor(s.scriptId) },
        )
    }
}

/** Survives process death: the prompter itself restarts at the editor. */
private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, List<String>>(
    save = { s ->
        when (s) {
            is Screen.Library -> listOf("library")
            is Screen.Editor -> listOf("editor", s.scriptId)
            is Screen.Prompter -> listOf("editor", s.scriptId)
        }
    },
    restore = { l -> if (l.size >= 2 && l[0] == "editor") Screen.Editor(l[1]) else Screen.Library },
)
