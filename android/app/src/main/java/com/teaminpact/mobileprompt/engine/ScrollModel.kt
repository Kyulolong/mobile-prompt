package com.teaminpact.mobileprompt.engine

import android.view.Choreographer

/** Monotonic seconds, same clock everywhere (recording start, word times, frames). */
object Clock {
    fun now(): Double = System.nanoTime() / 1_000_000_000.0
}

/** Where scrolling is applied. PromptTextView conforms. All values in px. */
interface ScrollSurface {
    var promptScrollTop: Float
    val promptViewportHeight: Float
    val promptContentHeight: Float
    val isUserDragging: Boolean
}

/**
 * Port of the iOS ScrollModel (itself a port of the web app's scroll.ts).
 *
 * Two layers of smoothing: "word creep" keeps inching forward at the measured
 * reading speed between STT updates, and a per-frame spring eases the scroll
 * position toward its target. Creep freezes on silence or when the aligner is
 * lost, so pauses hold position instead of drifting.
 *
 * Speeds and distances are specified in dp (= iOS points) and scaled by
 * [density] so the feel matches the iPhone build.
 */
class ScrollModel {
    var readingLineFrac = 0.38f
    var spring = 0.085f
    /** Hard cap on voice-follow scroll speed (dp/s). */
    var maxVoiceDpPerSec = 260f
    var creepCapTokens = 3.0
    var deadbandDp = 1.5f

    enum class Mode { VOICE, AUTO }
    var mode = Mode.VOICE
        set(v) { field = v; lastFrame = 0.0 }
    var autoDpPerSec = 45f
    var paused = false
        set(v) { field = v; lastFrame = 0.0 }

    var density = 1f
    var surface: ScrollSurface? = null
    /** Per-token center-Y (px) in content coordinates. */
    var offsets: FloatArray = FloatArray(0)

    private var confirmedToken = 0
    private var tokensPerSec = 2.5
    private var speaking = false
    private var lost = false
    private var running = false
    private var lastFrame = 0.0
    /** Last offset THIS controller wrote; any other move is snapped back. */
    private var lastWrittenTop = -1f
    private var creepTokens = 0.0
    private var prevDesired = 0.0
    private var prevConfirmed = 0

    fun update(
        confirmedToken: Int? = null,
        tokensPerSec: Double? = null,
        speaking: Boolean? = null,
        lost: Boolean? = null,
    ) {
        if (confirmedToken != null && confirmedToken != this.confirmedToken) {
            this.confirmedToken = confirmedToken
            creepTokens = 0.0
        }
        if (tokensPerSec != null) this.tokensPerSec = tokensPerSec
        if (speaking != null) this.speaking = speaking
        if (lost != null) this.lost = lost
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick(Clock.now())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrame = 0.0
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Immediately center a token (manual seek / start). */
    fun jump(to: Double) {
        val s = surface ?: return
        confirmedToken = to.toInt()
        prevConfirmed = to.toInt()
        prevDesired = to
        creepTokens = 0.0
        s.promptScrollTop = y(to) - s.promptViewportHeight * readingLineFrac
        lastWrittenTop = s.promptScrollTop
    }

    private fun y(index: Double): Float {
        if (offsets.isEmpty()) return 0f
        val i = maxOf(0, minOf(offsets.size - 1, index.toInt()))
        val f = (index - i).toFloat()
        val a = offsets[i]
        val b = offsets[minOf(offsets.size - 1, i + 1)]
        return a + (b - a) * maxOf(0f, minOf(1f, f))
    }

    fun tick(now: Double) {
        val s = surface
        if (s == null || s.isUserDragging) {
            lastFrame = 0.0
            lastWrittenTop = -1f // user owns the position; re-arm after the drag
            return
        }

        // Rogue-scroll guard: snap back anything that moved the view besides us.
        if (lastWrittenTop >= 0 && kotlin.math.abs(s.promptScrollTop - lastWrittenTop) > 30 * density) {
            s.promptScrollTop = lastWrittenTop
        }

        if (mode == Mode.AUTO) {
            val dt = if (lastFrame > 0) minOf(now - lastFrame, 0.1) else 0.0
            lastFrame = now
            if (!paused && autoDpPerSec > 0) {
                // Scroll until the LAST token reaches the reading line.
                val endTop = if (offsets.isEmpty()) {
                    maxOf(0f, s.promptContentHeight - s.promptViewportHeight)
                } else {
                    maxOf(0f, y((offsets.size - 1).toDouble()) - s.promptViewportHeight * readingLineFrac)
                }
                s.promptScrollTop = minOf(endTop, s.promptScrollTop + (autoDpPerSec * density * dt).toFloat())
            }
            lastWrittenTop = s.promptScrollTop
            return
        }

        val dt = if (lastFrame > 0) minOf(now - lastFrame, 0.1) else 1.0 / 60.0
        lastFrame = now
        if (offsets.isEmpty()) return

        if (speaking && !lost) {
            creepTokens = minOf(creepTokens + tokensPerSec * dt, creepCapTokens)
        }
        var desired = confirmedToken + creepTokens
        if (confirmedToken >= prevConfirmed) {
            desired = maxOf(desired, prevDesired) // forward reading: never regress
        }
        prevConfirmed = confirmedToken
        prevDesired = desired

        val targetTop = y(desired) - s.promptViewportHeight * readingLineFrac
        val diff = targetTop - s.promptScrollTop
        if (kotlin.math.abs(diff) > deadbandDp * density) {
            val maxStep = (maxVoiceDpPerSec * density * dt).toFloat()
            s.promptScrollTop += maxOf(-maxStep, minOf(maxStep, diff * spring))
        }
        lastWrittenTop = s.promptScrollTop
    }
}
