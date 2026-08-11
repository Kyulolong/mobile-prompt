/**
 * Real-time forced-alignment engine.
 *
 * Given the known script (as a jamo stream) and a live trickle of what the
 * speaker just said, it tracks which token they're currently on. It NEVER scans
 * the whole script per update — only a bounded window around the current cursor
 * — so a 10-minute script costs the same as a 10-second one.
 *
 * Behaviors, all expressed as scoring rules rather than special cases:
 *   verbatim  -> window match advances the cursor
 *   ad-lib    -> match falls below threshold -> cursor holds (no drift)
 *   pause     -> no tokens arrive -> cursor holds (creep is frozen upstream)
 *   skip      -> match lands ahead within the window -> jump
 *   re-read   -> small unpenalized look-back region absorbs it
 *   truly lost-> inverted-index re-seek jumps back onto the script
 */
import { tokenNorm as tokenJamo } from "./norm";
import type { PreparedScript } from "./script";

export interface AlignConfig {
  back: number; // jamo behind cursor the window may reach
  forwardBase: number; // extra jamo ahead of the query length
  accept: number; // max normalized edit distance to accept a match (0..1)
  reseekAccept: number; // stricter threshold for a global re-seek jump
  lostThreshold: number; // consecutive misses before we declare "lost"
  maxQueryJamo: number; // cap on how much recent speech we match on
  minQueryJamo: number; // below this we don't have enough to localize
  maxReseekCandidates: number;
  backwardPenalty: number; // per-jamo cost for matches behind the cursor (anti-backward-jitter)
  forwardPenalty: number; // gentle per-jamo cost for matches far ahead (anti-teleport)
}

export const DEFAULT_CONFIG: AlignConfig = {
  back: 26,
  forwardBase: 96,
  accept: 0.5,
  reseekAccept: 0.42,
  lostThreshold: 3,
  maxQueryJamo: 60,
  minQueryJamo: 4,
  maxReseekCandidates: 48,
  backwardPenalty: 0.3,
  forwardPenalty: 0.05,
};

export interface AlignResult {
  token: number; // best current token index
  moved: boolean; // did the cursor advance/jump this update
  lost: boolean; // engine can't find the position
  confidence: number; // 0..1 (1 = perfect match)
}

/**
 * Approximate substring match: cheapest way to align `query` ending somewhere
 * inside `text`. Returns how many text chars were consumed at the best end and
 * the edit distance there. Text chars before the match are free (start
 * anywhere); extra/again text chars cost 1 (insertions), as do misheard jamo.
 */
function fuzzyMatchEnd(
  query: string,
  text: string,
  cursorInWindow: number,
  backwardPenalty: number,
  forwardPenalty: number,
): { consumed: number; dist: number } {
  const m = query.length;
  const n = text.length;
  if (m === 0 || n === 0) return { consumed: 0, dist: m };
  let prev = new Int32Array(n + 1); // dp[0][*] = 0 : match may start anywhere
  let cur = new Int32Array(n + 1);
  for (let i = 1; i <= m; i++) {
    cur[0] = i; // consumed no text -> must delete i query chars
    const qc = query.charCodeAt(i - 1);
    for (let j = 1; j <= n; j++) {
      const sub = prev[j - 1] + (qc === text.charCodeAt(j - 1) ? 0 : 1);
      const delQ = prev[j] + 1;
      const insT = cur[j - 1] + 1;
      cur[j] = sub < delQ ? (sub < insT ? sub : insT) : delQ < insT ? delQ : insT;
    }
    const t = prev;
    prev = cur;
    cur = t;
  }
  // prev now holds dp[m][*]. Pick the best end, but SOFTLY penalize ends that
  // fall behind the current cursor — this keeps interim/ambiguous input from
  // yanking the reading position backward. Ties favor more progress (larger j).
  let bestJ = 0;
  let bestScore = Infinity;
  let bestDist = 0;
  const biased = cursorInWindow >= 0;
  for (let j = 1; j <= n; j++) {
    const behind = cursorInWindow - j; // >0 behind cursor, <0 ahead
    let penalty = 0;
    if (biased) penalty = behind > 0 ? behind * backwardPenalty : -behind * forwardPenalty;
    const score = prev[j] + penalty;
    if (score < bestScore || (score === bestScore && j > bestJ)) {
      bestScore = score;
      bestJ = j;
      bestDist = prev[j];
    }
  }
  return { consumed: bestJ, dist: bestDist };
}

export class Aligner {
  readonly script: PreparedScript;
  readonly cfg: AlignConfig;
  cursorJamo = 0;
  confirmedToken = 0;
  tokensPerSec = 2.5; // rough Korean reading default; refined as we go
  private lostCount = 0;
  private lastMoveTime = 0;
  private lastMoveToken = 0;

  constructor(script: PreparedScript, cfg: Partial<AlignConfig> = {}) {
    this.script = script;
    this.cfg = { ...DEFAULT_CONFIG, ...cfg };
  }

  reset(startToken = 0): void {
    this.confirmedToken = startToken;
    this.cursorJamo = this.script.tokens[startToken]?.jamoStart ?? 0;
    this.lostCount = 0;
    this.lastMoveTime = 0;
    this.tokensPerSec = 2.5;
  }

  /** Jump the cursor to a specific token (manual click / voice command). */
  seekToken(token: number): void {
    const t = Math.max(0, Math.min(this.script.tokens.length - 1, token));
    this.confirmedToken = t;
    this.cursorJamo = this.script.tokens[t]?.jamoStart ?? 0;
    this.lostCount = 0;
  }

  /** Build the jamo query from the most recent spoken words (bounded length). */
  private buildQuery(spokenTokens: string[]): string {
    let q = "";
    for (let i = spokenTokens.length - 1; i >= 0; i--) {
      const j = tokenJamo(spokenTokens[i]);
      if (j === "") continue;
      q = j + q;
      if (q.length >= this.cfg.maxQueryJamo) {
        q = q.slice(q.length - this.cfg.maxQueryJamo);
        break;
      }
    }
    return q;
  }

  push(spokenTokens: string[], now: number): AlignResult {
    const query = this.buildQuery(spokenTokens);
    if (query.length < this.cfg.minQueryJamo) {
      return { token: this.confirmedToken, moved: false, lost: this.lostCount >= this.cfg.lostThreshold, confidence: 0 };
    }

    const { jamo, jamoToToken } = this.script;
    const start = Math.max(0, this.cursorJamo - this.cfg.back);
    const end = Math.min(jamo.length, this.cursorJamo + query.length * 2 + this.cfg.forwardBase);
    const window = jamo.slice(start, end);
    const cursorInWindow = this.cursorJamo - start;

    const { consumed, dist } = fuzzyMatchEnd(query, window, cursorInWindow, this.cfg.backwardPenalty, this.cfg.forwardPenalty);
    const norm = dist / query.length;
    // Short fragments match spuriously all over the script, so demand a cleaner
    // match before letting them move the cursor (kills stray-word jitter).
    const accept = query.length < 12 ? this.cfg.accept * 0.7 : this.cfg.accept;

    if (consumed > 0 && norm <= accept) {
      const absEnd = start + consumed; // exclusive
      const token = jamoToToken[Math.min(absEnd - 1, jamoToToken.length - 1)];
      // Never snap backward on a merely-okay match (interim churn, repeated
      // phrases). Only a STRONG match may move the cursor back — a real re-read.
      if (token < this.confirmedToken && norm > this.cfg.reseekAccept) {
        this.lostCount = 0;
        return { token: this.confirmedToken, moved: false, lost: false, confidence: 1 - norm };
      }
      this.accept(token, absEnd, now);
      return { token, moved: true, lost: false, confidence: 1 - norm };
    }

    // miss
    this.lostCount++;
    if (this.lostCount >= this.cfg.lostThreshold) {
      const jump = this.reseek(query, spokenTokens);
      if (jump) {
        this.accept(jump.token, jump.absEnd, now);
        this.lostCount = 0;
        return { token: jump.token, moved: true, lost: false, confidence: 1 - jump.norm };
      }
    }
    return {
      token: this.confirmedToken,
      moved: false,
      lost: this.lostCount >= this.cfg.lostThreshold,
      confidence: Math.max(0, 1 - norm),
    };
  }

  private accept(token: number, absEnd: number, now: number): void {
    this.confirmedToken = token;
    this.cursorJamo = absEnd;
    this.lostCount = 0;
    if (this.lastMoveTime > 0) {
      const dt = (now - this.lastMoveTime) / 1000;
      const dTok = token - this.lastMoveToken;
      if (dt > 0.15 && dTok > 0 && dTok < 30) {
        const rate = dTok / dt;
        this.tokensPerSec = this.tokensPerSec * 0.75 + Math.min(rate, 8) * 0.25;
      }
    }
    this.lastMoveTime = now;
    this.lastMoveToken = token;
  }

  /**
   * Global recovery. Look up distinctive spoken tokens in the inverted index,
   * and fuzzy-match the full query around each candidate. If one clears the
   * (stricter) re-seek threshold, jump there.
   */
  private reseek(query: string, spokenTokens: string[]): { token: number; absEnd: number; norm: number } | null {
    const { jamo, jamoToToken, index } = this.script;
    const candidates: number[] = [];
    for (const raw of spokenTokens) {
      const j = tokenJamo(raw);
      if (j.length < 2) continue;
      const positions = index.get(j);
      if (positions) for (const p of positions) candidates.push(p);
      if (candidates.length >= this.cfg.maxReseekCandidates) break;
    }
    if (candidates.length === 0) return null;

    let best: { token: number; absEnd: number; norm: number } | null = null;
    const seen = new Set<number>();
    for (const tok of candidates) {
      const anchor = this.script.tokens[tok]?.jamoStart ?? 0;
      const start = Math.max(0, anchor - query.length);
      const bucket = start >> 5;
      if (seen.has(bucket)) continue;
      seen.add(bucket);
      const end = Math.min(jamo.length, anchor + query.length + 32);
      const window = jamo.slice(start, end);
      // global search: no cursor bias (cursorInWindow<0 disables locality)
      const { consumed, dist } = fuzzyMatchEnd(query, window, -1, 0, 0);
      if (consumed === 0) continue;
      const norm = dist / query.length;
      if (norm <= this.cfg.reseekAccept && (!best || norm < best.norm)) {
        const absEnd = start + consumed;
        best = { token: jamoToToken[Math.min(absEnd - 1, jamoToToken.length - 1)], absEnd, norm };
      }
    }
    return best;
  }
}
