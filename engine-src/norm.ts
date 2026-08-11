/**
 * Language-switchable token normalizer.
 *
 * The alignment engine compares STT output against the known script in a
 * normalized space. Korean uses jamo decomposition (jamo.ts, verbatim from the
 * web app). Latin-script languages (English first) use lowercase letters with
 * punctuation stripped and digits expanded to spoken words, so "2025" in the
 * script matches whether STT emits "2025" or "twenty twenty five".
 *
 * script.ts / align.ts import tokenNorm from here instead of tokenJamo —
 * that one-line import change is the only divergence from the web engine.
 */
import { tokenJamo, toJamo } from "./jamo";

export type EngineLang = "ko" | "en";

let lang: EngineLang = "ko";

export function setLang(l: EngineLang): void {
  lang = l;
}

export function tokenNorm(raw: string): string {
  return lang === "ko" ? tokenJamo(raw) : tokenLatin(raw);
}

// ---------- English / Latin ----------

const ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
  "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
  "eighteen", "nineteen"];
const TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

function belowHundred(n: number): string {
  if (n < 20) return ONES[n];
  const t = TENS[Math.floor(n / 10)];
  const r = n % 10;
  return r ? t + ONES[r] : t;
}

function belowThousand(n: number): string {
  const h = Math.floor(n / 100);
  const r = n % 100;
  let s = h ? ONES[h] + "hundred" : "";
  if (r) s += belowHundred(r);
  return s || "zero";
}

/** Deterministic spoken form for a digit string; both sides get the same one. */
function numberWords(digits: string): string {
  const n = parseInt(digits, 10);
  if (isNaN(n)) return digits;
  // Years read pairwise: 2025 -> "twenty twentyfive", 1999 -> "nineteen ninetynine"
  if (digits.length === 4 && n >= 1100 && n <= 2999 && n % 100 !== 0) {
    return belowHundred(Math.floor(n / 100)) + belowHundred(n % 100);
  }
  if (n < 100) return belowHundred(n);
  if (n < 1000) return belowThousand(n);
  if (n < 1_000_000) {
    const th = belowThousand(Math.floor(n / 1000)) + "thousand";
    const r = n % 1000;
    return r ? th + belowThousand(r) : th;
  }
  if (n < 1_000_000_000) {
    const m = belowThousand(Math.floor(n / 1_000_000)) + "million";
    const r = n % 1_000_000;
    return r ? m + numberWords(String(r)) : m;
  }
  return digits;
}

const ORDINAL_MAP: Record<string, string> = {
  one: "first", two: "second", three: "third", five: "fifth", eight: "eighth",
  nine: "ninth", twelve: "twelfth",
};

export function tokenLatin(raw: string): string {
  let s = raw.toLowerCase();
  // strip diacritics (café -> cafe); recompose so Hangul syllables — which
  // NFD also decomposes — come back intact for the filter below
  s = s.normalize("NFD").replace(/[̀-ͯ]/g, "").normalize("NFC");
  s = s.replace(/%/g, " percent ").replace(/&/g, " and ").replace(/\+/g, " plus ");
  // ordinals: 21st -> twentyfirst (approximate: cardinal + suffix fix)
  s = s.replace(/(\d+)(st|nd|rd|th)\b/g, (_m, d: string) => {
    const w = numberWords(d);
    for (const [card, ord] of Object.entries(ORDINAL_MAP)) {
      if (w.endsWith(card)) return w.slice(0, w.length - card.length) + ord;
    }
    if (w.endsWith("y")) return w.slice(0, -1) + "ieth";
    return w + "th";
  });
  s = s.replace(/\d+/g, (d) => numberWords(d));
  // Keep Latin letters AND Hangul (drops punctuation: don't -> dont).
  // Hangul survives so an English-base script with embedded Korean sentences
  // still aligns — mirroring how the Korean path keeps Latin.
  s = s.replace(/[^a-z가-힣]/g, "");
  // Decompose any Hangul to jamo; Latin passes through unchanged.
  return toJamo(s);
}
