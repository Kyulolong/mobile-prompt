package com.teaminpact.mobileprompt

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.teaminpact.mobileprompt.ui.AppRoot
import com.teaminpact.mobileprompt.ui.MobilePromptTheme

class MainActivity : AppCompatActivity() {
    private val app get() = application as MobilePromptApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            importSharedText(intent)
            SelfTest.maybeRun(this, intent)
        }
        setContent {
            MobilePromptTheme {
                AppRoot(settings = app.settings, store = app.scriptStore)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importSharedText(intent)
        SelfTest.maybeRun(this, intent)
    }

    /** "Share to prompter": text from any app's share sheet becomes a new script. */
    private fun importSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (text.isBlank()) return
        app.scriptStore.add(text)
        // Consume so a configuration change doesn't re-import.
        intent.action = null
    }
}
