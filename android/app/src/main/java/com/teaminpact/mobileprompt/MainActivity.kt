package com.teaminpact.mobileprompt

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.teaminpact.mobileprompt.model.HandoffRequest
import com.teaminpact.mobileprompt.ui.AppRoot
import com.teaminpact.mobileprompt.ui.MobilePromptTheme

class MainActivity : AppCompatActivity() {
    private val app get() = application as MobilePromptApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncoming(intent)
            SelfTest.maybeRun(this, intent)
        }
        setContent {
            MobilePromptTheme {
                AppRoot(settings = app.settings, store = app.scriptStore, pendingOpen = app.pendingOpen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
        SelfTest.maybeRun(this, intent)
    }

    /**
     * Two ways a script arrives from another app:
     *  - the share sheet (ACTION_SEND text/plain) → a new script in the library
     *  - the Perplz handoff (voiceprompter://script?s=…) → a new script, opened
     *    right away (editor, or the prompter itself with autostart=1)
     */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) return
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                if (text.isBlank()) return
                app.scriptStore.add(text)
            }
            Intent.ACTION_VIEW -> {
                val request = HandoffRequest.parse(intent.dataString) ?: return
                val script = app.scriptStore.add(request.text)
                app.pendingOpen.value = PendingOpen(script.id, script.text, request.autostart, request.returnUrl)
            }
            else -> return
        }
        // Consume so a configuration change doesn't re-import.
        intent.action = null
    }
}
