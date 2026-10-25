package com.teaminpact.mobileprompt

import android.content.Context
import android.content.Intent

/** Release builds carry no self test. See src/debug for the real one. */
object SelfTest {
    @Suppress("UNUSED_PARAMETER")
    fun maybeRun(context: Context, intent: Intent?) = Unit
}
