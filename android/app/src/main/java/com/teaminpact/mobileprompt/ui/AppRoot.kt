package com.teaminpact.mobileprompt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.teaminpact.mobileprompt.PendingOpen
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore

/** Three screens, one stack: library → editor → prompter. */
sealed class Screen {
    object Library : Screen()
    /** `returnUrl`: where "Back to Perplz" goes, when the script came by handoff. */
    data class Editor(val scriptId: String, val returnUrl: String? = null) : Screen()
    data class Prompter(val scriptId: String, val script: String, val returnUrl: String? = null) : Screen()
}

@Composable
fun AppRoot(settings: AppSettings, store: ScriptStore, pendingOpen: MutableState<PendingOpen?>) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Library) }

    // A handed-off script jumps straight to its editor, or to the prompter with autostart.
    LaunchedEffect(pendingOpen.value) {
        val p = pendingOpen.value ?: return@LaunchedEffect
        pendingOpen.value = null
        screen = if (p.autostart) Screen.Prompter(p.scriptId, p.text, p.returnUrl)
                 else Screen.Editor(p.scriptId, p.returnUrl)
    }

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
                onStart = { text -> screen = Screen.Prompter(s.scriptId, text, s.returnUrl) },
            )
        }
        // Keyed so a handoff arriving while the prompter is open replaces the
        // session instead of recomposing the old one with a new script.
        is Screen.Prompter -> key(s) {
            PrompterScreen(
                script = s.script,
                settings = settings,
                returnUrl = s.returnUrl,
                onClose = { screen = Screen.Editor(s.scriptId, s.returnUrl) },
            )
        }
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
