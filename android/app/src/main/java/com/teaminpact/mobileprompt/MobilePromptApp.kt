package com.teaminpact.mobileprompt

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore

/** A script that arrived through the `voiceprompter://` handoff; AppRoot opens it once. */
class PendingOpen(val scriptId: String, val text: String, val autostart: Boolean, val returnUrl: String?)

/** Process-wide singletons: settings and the script library. */
class MobilePromptApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var scriptStore: ScriptStore
        private set
    val pendingOpen = mutableStateOf<PendingOpen?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        scriptStore = ScriptStore(this)
    }
}
