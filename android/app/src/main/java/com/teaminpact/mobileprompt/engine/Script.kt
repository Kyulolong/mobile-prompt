package com.teaminpact.mobileprompt.engine

/** One display token (eojeol) of the script. Port of engine-src/script.ts. */
class ScriptToken(
    /** original text for display (keeps punctuation/case) */
    val raw: String,
    /** normalized jamo used for matching (may be empty for pure emoji/punct) */
    val jamo: String,
    /** true if a line break precedes this token in the source */
    val breakBefore: Boolean,
    /** start offset of this token inside PreparedScript.jamo */
    val jamoStart: Int,
)

class PreparedScript(
    val tokens: List<ScriptToken>,
    /** concatenation of every token's jamo, no separators */
    val jamo: String,
    /** jamoToToken[i] = index of the token that jamo char i belongs to */
    val jamoToToken: IntArray,
    /** canonical token jamo -> token indices where it appears */
    val index: Map<String, IntArray>,
    val norm: TokenNormalizer,
) {
    /**
     * jamo n-gram -> start positions in [jamo]. Recovery anchor for recognizers
     * that emit no word spacing (sherpa-onnx's Korean model), where no spoken
     * "word" ever equals a script eojeol. Built on first use.
     */
    val ngramIndex: Map<String, IntArray> by lazy {
        val m = HashMap<String, MutableList<Int>>()
        var i = 0
        while (i + NGRAM <= jamo.length) {
            m.getOrPut(jamo.substring(i, i + NGRAM)) { ArrayList(2) }.add(i)
            i++
        }
        m.mapValues { it.value.toIntArray() }
    }

    companion object {
        const val NGRAM = 6
    }
}

// JS `\s` is Unicode-aware; Java's is ASCII-only, so spell the set out.
private val WHITESPACE = Regex("[ \\t\\n\\u000B\\f\\r\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

/**
 * Turns a raw script string into the structures the aligner needs: display
 * tokens, one big jamo stream with a jamo-index -> token-index map, and an
 * inverted index of distinctive tokens for "I'm lost" re-seeking.
 */
fun prepareScript(text: String, norm: TokenNormalizer): PreparedScript {
    val tokens = ArrayList<ScriptToken>()
    val jamo = StringBuilder()
    val jamoToToken = ArrayList<Int>()
    var pendingBreak = false

    fun addToken(part: String) {
        val j = norm.norm(part)
        val tokenIdx = tokens.size
        val jamoStart = jamo.length
        tokens.add(ScriptToken(raw = part, jamo = j, breakBefore = pendingBreak, jamoStart = jamoStart))
        for (k in 0 until j.length) jamoToToken.add(tokenIdx)
        jamo.append(j)
        pendingBreak = false
    }

    var pos = 0
    for (m in WHITESPACE.findAll(text)) {
        if (m.range.first > pos) addToken(text.substring(pos, m.range.first))
        if (m.value.contains('\n')) pendingBreak = true
        pos = m.range.last + 1
    }
    if (pos < text.length) addToken(text.substring(pos))

    // Inverted index for re-seek. Only reasonably distinctive tokens (>= 2 jamo)
    // are worth indexing as anchors.
    val index = HashMap<String, MutableList<Int>>()
    tokens.forEachIndexed { i, t ->
        if (t.jamo.length < 2) return@forEachIndexed
        index.getOrPut(t.jamo) { ArrayList() }.add(i)
    }

    return PreparedScript(
        tokens = tokens,
        jamo = jamo.toString(),
        jamoToToken = jamoToToken.toIntArray(),
        index = index.mapValues { it.value.toIntArray() },
        norm = norm,
    )
}
