package com.teaminpact.mobileprompt.engine

/** Aligner tuning knobs. Port of AlignConfig in engine-src/align.ts. */
data class AlignConfig(
    /** jamo behind cursor the window may reach */
    val back: Int = 26,
    /** extra jamo ahead of the query length */
    val forwardBase: Int = 96,
    /** max normalized edit distance to accept a match (0..1) */
    val accept: Double = 0.5,
    /** stricter threshold for a global re-seek jump */
    val reseekAccept: Double = 0.42,
    /** consecutive misses before we declare "lost" */
    val lostThreshold: Int = 3,
    /** cap on how much recent speech we match on */
    val maxQueryJamo: Int = 60,
    /** below this we don't have enough to localize */
    val minQueryJamo: Int = 4,
    val maxReseekCandidates: Int = 48,
    /** per-jamo cost for matches behind the cursor (anti-backward-jitter) */
    val backwardPenalty: Double = 0.3,
    /** gentle per-jamo cost for matches far ahead (anti-teleport) */
    val forwardPenalty: Double = 0.05,
    /**
     * Android extension (off = exact web/iOS behavior): when no spoken word
     * matches a script token exactly, re-seek by voting with jamo n-grams.
     * Needed for recognizers that output Korean without word spacing.
     */
    val ngramReseek: Boolean = false,
)

class AlignResult(
    /** best current token index */
    val token: Int,
    /** did the cursor advance/jump this update */
    val moved: Boolean,
    /** engine can't find the position */
    val lost: Boolean,
    /** 0..1 (1 = perfect match) */
    val confidence: Double,
)

private class MatchEnd(val consumed: Int, val dist: Int)

/**
 * Approximate substring match: cheapest way to align `query` ending somewhere
 * inside `text`. Returns how many text chars were consumed at the best end and
 * the edit distance there. Text chars before the match are free (start
 * anywhere); extra text chars cost 1 (insertions), as do misheard jamo.
 */
private fun fuzzyMatchEnd(
    query: String,
    text: String,
    cursorInWindow: Int,
    backwardPenalty: Double,
    forwardPenalty: Double,
): MatchEnd {
    val m = query.length
    val n = text.length
    if (m == 0 || n == 0) return MatchEnd(0, m)
    var prev = IntArray(n + 1) // dp[0][*] = 0 : match may start anywhere
    var cur = IntArray(n + 1)
    for (i in 1..m) {
        cur[0] = i // consumed no text -> must delete i query chars
        val qc = query[i - 1]
        for (j in 1..n) {
            val sub = prev[j - 1] + (if (qc == text[j - 1]) 0 else 1)
            val delQ = prev[j] + 1
            val insT = cur[j - 1] + 1
            cur[j] = if (sub < delQ) (if (sub < insT) sub else insT) else (if (delQ < insT) delQ else insT)
        }
        val t = prev
        prev = cur
        cur = t
    }
    // prev now holds dp[m][*]. Pick the best end, but SOFTLY penalize ends that
    // fall behind the current cursor — this keeps interim/ambiguous input from
    // yanking the reading position backward. Ties favor more progress (larger j).
    var bestJ = 0
    var bestScore = Double.POSITIVE_INFINITY
    var bestDist = 0
    val biased = cursorInWindow >= 0
    for (j in 1..n) {
        val behind = cursorInWindow - j // >0 behind cursor, <0 ahead
        var penalty = 0.0
        if (biased) penalty = if (behind > 0) behind * backwardPenalty else -behind * forwardPenalty
        val score = prev[j] + penalty
        if (score < bestScore || (score == bestScore && j > bestJ)) {
            bestScore = score
            bestJ = j
            bestDist = prev[j]
        }
    }
    return MatchEnd(bestJ, bestDist)
}

/**
 * Real-time forced-alignment engine (port of engine-src/align.ts).
 *
 * Given the known script (as a jamo stream) and a live trickle of what the
 * speaker just said, it tracks which token they're currently on. It never
 * scans the whole script per update — only a bounded window around the cursor.
 */
class Aligner(val script: PreparedScript, val cfg: AlignConfig = AlignConfig()) {
    var cursorJamo = 0
        private set
    var confirmedToken = 0
        private set
    /** rough Korean reading default; refined as we go */
    var tokensPerSec = 2.5
        private set
    private var lostCount = 0
    private var lastMoveTime = 0.0
    private var lastMoveToken = 0

    fun reset(startToken: Int = 0) {
        confirmedToken = startToken
        cursorJamo = script.tokens.getOrNull(startToken)?.jamoStart ?: 0
        lostCount = 0
        lastMoveTime = 0.0
        tokensPerSec = 2.5
    }

    /** Jump the cursor to a specific token (manual tap). */
    fun seekToken(token: Int) {
        val t = maxOf(0, minOf(script.tokens.size - 1, token))
        confirmedToken = t
        cursorJamo = script.tokens.getOrNull(t)?.jamoStart ?: 0
        lostCount = 0
    }

    /** Build the jamo query from the most recent spoken words (bounded length). */
    private fun buildQuery(spokenTokens: List<String>): String {
        var q = ""
        for (i in spokenTokens.indices.reversed()) {
            val j = script.norm.norm(spokenTokens[i])
            if (j.isEmpty()) continue
            q = j + q
            if (q.length >= cfg.maxQueryJamo) {
                q = q.substring(q.length - cfg.maxQueryJamo)
                break
            }
        }
        return q
    }

    /** `now` is in milliseconds (the web engine's performance.now()). */
    fun push(spokenTokens: List<String>, now: Double): AlignResult {
        val query = buildQuery(spokenTokens)
        if (query.length < cfg.minQueryJamo) {
            return AlignResult(confirmedToken, moved = false, lost = lostCount >= cfg.lostThreshold, confidence = 0.0)
        }

        val jamo = script.jamo
        val jamoToToken = script.jamoToToken
        val start = maxOf(0, cursorJamo - cfg.back)
        val end = minOf(jamo.length, cursorJamo + query.length * 2 + cfg.forwardBase)
        val window = jamo.substring(start, end)
        val cursorInWindow = cursorJamo - start

        val match = fuzzyMatchEnd(query, window, cursorInWindow, cfg.backwardPenalty, cfg.forwardPenalty)
        val norm = match.dist.toDouble() / query.length
        // Short fragments match spuriously all over the script, so demand a cleaner
        // match before letting them move the cursor (kills stray-word jitter).
        val accept = if (query.length < 12) cfg.accept * 0.7 else cfg.accept

        if (match.consumed > 0 && norm <= accept) {
            val absEnd = start + match.consumed // exclusive
            val token = jamoToToken[minOf(absEnd - 1, jamoToToken.size - 1)]
            // Never snap backward on a merely-okay match (interim churn, repeated
            // phrases). Only a STRONG match may move the cursor back — a real re-read.
            if (token < confirmedToken && norm > cfg.reseekAccept) {
                lostCount = 0
                return AlignResult(confirmedToken, moved = false, lost = false, confidence = 1 - norm)
            }
            accept(token, absEnd, now)
            return AlignResult(token, moved = true, lost = false, confidence = 1 - norm)
        }

        // miss
        lostCount++
        if (lostCount >= cfg.lostThreshold) {
            val jump = reseek(query, spokenTokens)
            if (jump != null) {
                accept(jump.token, jump.absEnd, now)
                lostCount = 0
                return AlignResult(jump.token, moved = true, lost = false, confidence = 1 - jump.norm)
            }
        }
        return AlignResult(
            confirmedToken,
            moved = false,
            lost = lostCount >= cfg.lostThreshold,
            confidence = maxOf(0.0, 1 - norm),
        )
    }

    private fun accept(token: Int, absEnd: Int, now: Double) {
        confirmedToken = token
        cursorJamo = absEnd
        lostCount = 0
        if (lastMoveTime > 0) {
            val dt = (now - lastMoveTime) / 1000
            val dTok = token - lastMoveToken
            if (dt > 0.15 && dTok > 0 && dTok < 30) {
                val rate = dTok / dt
                tokensPerSec = tokensPerSec * 0.75 + minOf(rate, 8.0) * 0.25
            }
        }
        lastMoveTime = now
        lastMoveToken = token
    }

    private class Jump(val token: Int, val absEnd: Int, val norm: Double)

    /**
     * Global recovery. Look up distinctive spoken tokens in the inverted index,
     * and fuzzy-match the full query around each candidate. If one clears the
     * (stricter) re-seek threshold, jump there.
     */
    private fun reseek(query: String, spokenTokens: List<String>): Jump? {
        val jamo = script.jamo
        val jamoToToken = script.jamoToToken
        val candidates = ArrayList<Int>()
        for (raw in spokenTokens) {
            val j = script.norm.norm(raw)
            if (j.length < 2) continue
            script.index[j]?.let { positions -> for (p in positions) candidates.add(p) }
            if (candidates.size >= cfg.maxReseekCandidates) break
        }
        // Anchors are jamo positions; the exact-token path maps through jamoStart.
        val anchors = ArrayList<Int>(candidates.size)
        for (tok in candidates) anchors.add(script.tokens.getOrNull(tok)?.jamoStart ?: 0)
        if (anchors.isEmpty() && cfg.ngramReseek) anchors.addAll(ngramAnchors(query))
        if (anchors.isEmpty()) return null

        var best: Jump? = null
        val seen = HashSet<Int>()
        for (anchor in anchors) {
            val start = maxOf(0, anchor - query.length)
            val bucket = start shr 5
            if (!seen.add(bucket)) continue
            val end = minOf(jamo.length, anchor + query.length + 32)
            val window = jamo.substring(start, end)
            // global search: no cursor bias (cursorInWindow<0 disables locality)
            val match = fuzzyMatchEnd(query, window, -1, 0.0, 0.0)
            if (match.consumed == 0) continue
            val norm = match.dist.toDouble() / query.length
            if (norm <= cfg.reseekAccept && (best == null || norm < best.norm)) {
                val absEnd = start + match.consumed
                best = Jump(jamoToToken[minOf(absEnd - 1, jamoToToken.size - 1)], absEnd, norm)
            }
        }
        return best
    }

    /**
     * Vote 32-jamo buckets of the script with the query's n-gram shingles and
     * return the best-supported bucket starts (most votes first). The fuzzy
     * match around each anchor still has to clear `reseekAccept`, so a stray
     * shingle hit never jumps on its own.
     */
    private fun ngramAnchors(query: String): List<Int> {
        val n = PreparedScript.NGRAM
        if (query.length < n) return emptyList()
        val votes = HashMap<Int, Int>()
        var i = 0
        while (i + n <= query.length) {
            script.ngramIndex[query.substring(i, i + n)]?.forEach { p ->
                val bucket = p shr 5
                votes[bucket] = (votes[bucket] ?: 0) + 1
            }
            i += 2
        }
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .map { it.key shl 5 }
    }
}
