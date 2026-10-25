package com.teaminpact.mobileprompt

import android.app.Application
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.model.ScriptStore

/** Process-wide singletons: settings and the script library. */
class MobilePromptApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var scriptStore: ScriptStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        scriptStore = ScriptStore(this)
    }
}
