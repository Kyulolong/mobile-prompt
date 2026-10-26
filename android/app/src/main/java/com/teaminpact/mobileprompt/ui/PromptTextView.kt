package com.teaminpact.mobileprompt.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.teaminpact.mobileprompt.engine.Clock
import com.teaminpact.mobileprompt.engine.DisplayToken
import com.teaminpact.mobileprompt.engine.ScrollSurface
import kotlin.math.roundToInt

/**
 * Renders the script with a plain TextView inside a ScrollView so we get exact
 * per-token line positions (for voice-follow scroll) and cheap current-word
 * highlighting via span edits — nothing in Compose ever re-renders the script.
 * Port of the iOS PromptTextView / ScriptTextView pair.
 */
class PromptTextView(context: Context) : ScrollView(context), ScrollSurface {
    /** Fired after a layout pass that changed the viewport size or the text. */
    var onLayoutChange: (() -> Unit)? = null
    /** Tap on the script: character index under the finger. */
    var onTap: ((Int) -> Unit)? = null

    private val textView = TextView(context)
    /** Character range of each token inside the text. */
    var ranges: List<IntRange> = emptyList()
        private set
    private var readingLineFrac = 0.38f
    private var highlightSpan: ForegroundColorSpan? = null
    private var lastLayoutSize = 0 to 0

    // Touch bookkeeping for ScrollSurface.isUserDragging.
    private var touching = false
    private var lastUserScrollAt = 0.0
    private var programmatic = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val layout = textView.layout ?: return false
            val y = e.y + scrollY - textView.top - textView.paddingTop
            val x = e.x - textView.left - textView.paddingLeft
            val line = layout.getLineForVertical(y.roundToInt())
            onTap?.invoke(layout.getOffsetForHorizontal(line, x))
            return true
        }
    })

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        clipToPadding = false
        textView.setTextColor(Color.WHITE)
        textView.typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
        textView.setShadowLayer(4f * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, 0xE6000000.toInt())
        textView.ellipsize = null
        textView.isClickable = false
        textView.isFocusable = false
        addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // MARK: content

    fun setContent(tokens: List<DisplayToken>, fontSizePx: Float, locale: java.util.Locale) {
        if (textView.textLocale != locale) textView.textLocale = locale
        val keepHangulWords = locale.language == "ko"
        val sb = SpannableStringBuilder()
        val r = ArrayList<IntRange>(tokens.size)
        val paraExtra = (fontSizePx * 0.6f).roundToInt()
        for ((i, t) in tokens.withIndex()) {
            if (i > 0) sb.append(if (t.breakBefore) "\n" else " ")
            val start = sb.length
            sb.append(if (keepHangulWords) joinHangul(t.raw) else t.raw)
            r.add(start until sb.length)
            if (i + 1 < tokens.size && tokens[i + 1].breakBefore) {
                // Paragraph spacing: extra room below the last line of a paragraph.
                sb.setSpan(ParagraphBottomSpan(paraExtra), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        ranges = r
        highlightSpan = null
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
        textView.setLineSpacing(fontSizePx * 0.4f, 1f)
        textView.setText(sb, TextView.BufferType.SPANNABLE)
        applyInsets()
    }

    /**
     * Android's line breaker splits Korean between any two syllables (like
     * CSS without `keep-all`), which reads badly on a prompter. A zero-width
     * WORD JOINER between Hangul syllables keeps each eojeol on one line.
     */
    private fun joinHangul(raw: String): String {
        val out = StringBuilder(raw.length * 2)
        var prevHangul = false
        for (c in raw) {
            val hangul = c in '가'..'힣'
            if (hangul && prevHangul) out.append('⁠')
            out.append(c)
            prevHangul = hangul
        }
        return out.toString()
    }

    fun setReadingLineFrac(frac: Float) {
        if (frac == readingLineFrac) return
        readingLineFrac = frac
        applyInsets()
    }

    /** Inset so the first/last token can reach the reading line. */
    private fun applyInsets() {
        val h = height
        if (h <= 0) return
        val top = (h * readingLineFrac).roundToInt()
        val bottom = (h * (1 - readingLineFrac)).roundToInt()
        val hPad = (14 * resources.displayMetrics.density).roundToInt()
        if (textView.paddingTop != top || textView.paddingBottom != bottom || textView.paddingLeft != hPad) {
            textView.setPadding(hPad, top, hPad, bottom)
        }
    }

    /** Per-token center-Y offsets in content coordinates; empty before layout. */
    fun tokenOffsets(): FloatArray {
        val layout = textView.layout ?: return FloatArray(0)
        val inset = textView.paddingTop + textView.top
        return FloatArray(ranges.size) { i ->
            val line = layout.getLineForOffset(ranges[i].first)
            (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + inset
        }
    }

    /** Move the current-word highlight. `color` null = highlighting off. */
    fun setHighlight(tokenIndex: Int, color: Int?) {
        val text = textView.text as? Spannable ?: return
        highlightSpan?.let { text.removeSpan(it) }
        highlightSpan = null
        if (color == null || tokenIndex < 0 || tokenIndex >= ranges.size) return
        val span = ForegroundColorSpan(color)
        val range = ranges[tokenIndex]
        text.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlightSpan = span
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val size = width to height
        if (size != lastLayoutSize) {
            lastLayoutSize = size
            // Changing padding here would re-enter layout; defer one frame.
            post { applyInsets() }
        }
        if (textView.layout != null) onLayoutChange?.invoke()
    }

    // MARK: touch

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!programmatic) lastUserScrollAt = Clock.now()
    }

    // MARK: ScrollSurface

    override var promptScrollTop: Float
        get() = scrollY.toFloat()
        set(value) {
            programmatic = true
            scrollTo(0, value.roundToInt())
            programmatic = false
        }
    override val promptViewportHeight: Float get() = height.toFloat()
    override val promptContentHeight: Float get() = textView.height.toFloat()
    /** Finger down, or a fling still moving the view in the last ~0.12 s. */
    override val isUserDragging: Boolean
        get() = touching || Clock.now() - lastUserScrollAt < 0.12

    /**
     * Extra space below the last line of a paragraph (TextView has no
     * paragraph spacing). Not above the first line: StaticLayout carries a
     * line's chosen metrics into the following lines of the same paragraph,
     * so a taller first line made every wrapped line taller. Metrics reset at
     * a paragraph's end, so growing its last line touches nothing else.
     */
    private class ParagraphBottomSpan(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val spanEnd = (text as Spanned).getSpanEnd(this)
            if (spanEnd in start + 1..end) {
                fm.descent += extra
                fm.bottom += extra
            }
        }
    }
}
