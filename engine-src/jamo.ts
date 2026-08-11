/**
 * Korean text normalization for forced alignment.
 *
 * The whole trick of this app: we already know the script, so STT doesn't need
 * to be accurate — it only needs to be *close enough* to localize the reading
 * position. Comparing at the JAMO (자모) level is what makes "close enough" work
 * for Korean: if STT mishears a single 받침 (final consonant), that's ONE jamo
 * edit instead of a whole-syllable miss.
 *
 * No external dependency — Hangul syllable decomposition is pure Unicode math.
 */

const CHO = ["ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"];
const JUNG = ["ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ"];
// Final consonants; compound ones (ㄳ, ㄵ…) are split into two so a partial
// mishearing costs a single edit rather than a whole cluster.
const JONG = ["", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"];

const HANGUL_BASE = 0xac00;
const HANGUL_LAST = 0xd7a3;

/** Decompose one code point into its jamo string (non-Hangul passes through). */
function jamoOfCodePoint(code: number): string {
  if (code < HANGUL_BASE || code > HANGUL_LAST) return String.fromCodePoint(code);
  const s = code - HANGUL_BASE;
  const jong = s % 28;
  const jung = ((s - jong) / 28) % 21;
  const cho = Math.floor((s - jong) / 28 / 21);
  return CHO[cho] + JUNG[jung] + JONG[jong];
}

// --- Sino-Korean number reading (2025 -> 이천이십오) ---------------------------
const DIGIT = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"];
const SMALL_UNIT = ["", "십", "백", "천"];
const BIG_UNIT = ["", "만", "억", "조", "경"];

/** Read an integer string as Sino-Korean. Best-effort; used only for matching. */
export function sinoKorean(numStr: string): string {
  let digits = numStr.replace(/[^0-9]/g, "");
  if (digits === "") return "";
  digits = digits.replace(/^0+(?=\d)/, "");
  if (digits === "0") return "영";
  // split into 4-digit groups from the right
  const groups: string[] = [];
  for (let i = digits.length; i > 0; i -= 4) groups.unshift(digits.slice(Math.max(0, i - 4), i));
  let out = "";
  const g = groups.length;
  for (let gi = 0; gi < g; gi++) {
    const group = groups[gi];
    let chunk = "";
    const len = group.length;
    for (let i = 0; i < len; i++) {
      const d = group.charCodeAt(i) - 48;
      if (d === 0) continue;
      const unit = SMALL_UNIT[len - 1 - i];
      // 1 is implicit before 십/백/천 (십, not 일십)
      chunk += (d === 1 && unit !== "" ? "" : DIGIT[d]) + unit;
    }
    if (chunk !== "") out += chunk + BIG_UNIT[g - 1 - gi];
  }
  return out;
}

/**
 * Normalize a raw token (eojeol) into a canonical string, then to jamo.
 * - lowercases Latin so "AI"/"ai" match
 * - rewrites digit runs to their Sino-Korean reading so "2025" and "이천이십오"
 *   land on the same jamo, whichever the STT emits
 * - drops emoji / punctuation that neither side reliably produces
 */
export function canonical(token: string): string {
  let t = token.toLowerCase().normalize("NFC");
  t = t.replace(/\d+/g, (m) => sinoKorean(m));
  // strip everything except Hangul syllables, Latin letters, and digits
  t = t.replace(/[^가-힣a-z0-9]/g, "");
  return t;
}

/** Convert an arbitrary string to its jamo stream (spaces removed). */
export function toJamo(text: string): string {
  let out = "";
  for (const ch of text) out += jamoOfCodePoint(ch.codePointAt(0)!);
  return out;
}

/** canonical() then toJamo() — the form the aligner actually compares. */
export function tokenJamo(token: string): string {
  return toJamo(canonical(token));
}
