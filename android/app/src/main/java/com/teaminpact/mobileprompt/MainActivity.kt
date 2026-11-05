package com.teaminpact.mobileprompt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.teaminpact.mobileprompt.model.HandoffRequest
import com.teaminpact.mobileprompt.model.TextDecoding
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

    /** True while a volume/camera press we acted on is still held, so its repeats and UP are swallowed too. */
    private var shutterHeld = false

    /**
     * Hardware shutter: while the prompter is up, volume buttons (and a
     * Bluetooth selfie remote, which sends VOLUME_UP or CAMERA) start/stop
     * recording instead of changing the volume. Elsewhere they do their job.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isShutterKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_CAMERA
        if (isShutterKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        shutterHeld = app.hardwareShutter?.invoke() == true
                    }
                    if (shutterHeld) return true
                }
                KeyEvent.ACTION_UP -> if (shutterHeld) {
                    shutterHeld = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Two ways a script arrives from another app:
     *  - the share sheet (ACTION_SEND with a text MIME type: inline text or a
     *    text file) → a new script in the library
     *  - the Perplz handoff (voiceprompter://script?s=…) → a new script, opened
     *    right away (editor, or the prompter itself with autostart=1)
     */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) return
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let { TextDecoding.read(contentResolver, it) }
                    ?: return
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
