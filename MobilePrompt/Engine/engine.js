(() => {
  var __defProp = Object.defineProperty;
  var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
  var __publicField = (obj, key, value) => __defNormalProp(obj, typeof key !== "symbol" ? key + "" : key, value);

  // jamo.ts
  var CHO = ["\u3131", "\u3132", "\u3134", "\u3137", "\u3138", "\u3139", "\u3141", "\u3142", "\u3143", "\u3145", "\u3146", "\u3147", "\u3148", "\u3149", "\u314A", "\u314B", "\u314C", "\u314D", "\u314E"];
  var JUNG = ["\u314F", "\u3150", "\u3151", "\u3152", "\u3153", "\u3154", "\u3155", "\u3156", "\u3157", "\u3158", "\u3159", "\u315A", "\u315B", "\u315C", "\u315D", "\u315E", "\u315F", "\u3160", "\u3161", "\u3162", "\u3163"];
  var JONG = ["", "\u3131", "\u3132", "\u3131\u3145", "\u3134", "\u3134\u3148", "\u3134\u314E", "\u3137", "\u3139", "\u3139\u3131", "\u3139\u3141", "\u3139\u3142", "\u3139\u3145", "\u3139\u314C", "\u3139\u314D", "\u3139\u314E", "\u3141", "\u3142", "\u3142\u3145", "\u3145", "\u3146", "\u3147", "\u3148", "\u314A", "\u314B", "\u314C", "\u314D", "\u314E"];
  var HANGUL_BASE = 44032;
  var HANGUL_LAST = 55203;
  function jamoOfCodePoint(code) {
    if (code < HANGUL_BASE || code > HANGUL_LAST) return String.fromCodePoint(code);
    const s = code - HANGUL_BASE;
    const jong = s % 28;
    const jung = (s - jong) / 28 % 21;
    const cho = Math.floor((s - jong) / 28 / 21);
    return CHO[cho] + JUNG[jung] + JONG[jong];
  }
  var DIGIT = ["", "\uC77C", "\uC774", "\uC0BC", "\uC0AC", "\uC624", "\uC721", "\uCE60", "\uD314", "\uAD6C"];
  var SMALL_UNIT = ["", "\uC2ED", "\uBC31", "\uCC9C"];
  var BIG_UNIT = ["", "\uB9CC", "\uC5B5", "\uC870", "\uACBD"];
  function sinoKorean(numStr) {
    let digits = numStr.replace(/[^0-9]/g, "");
    if (digits === "") return "";
    digits = digits.replace(/^0+(?=\d)/, "");
    if (digits === "0") return "\uC601";
    const groups = [];
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
        chunk += (d === 1 && unit !== "" ? "" : DIGIT[d]) + unit;
      }
      if (chunk !== "") out += chunk + BIG_UNIT[g - 1 - gi];
    }
    return out;
  }
  function canonical(token) {
    let t = token.toLowerCase().normalize("NFC");
    t = t.replace(/\d+/g, (m) => sinoKorean(m));
    t = t.replace(/[^가-힣a-z0-9]/g, "");
    return t;
  }
  function toJamo(text) {
    let out = "";
    for (const ch of text) out += jamoOfCodePoint(ch.codePointAt(0));
    return out;
  }
  function tokenJamo(token) {
    return toJamo(canonical(token));
  }

  // norm.ts
  var lang = "ko";
  function setLang(l) {
    lang = l;
  }
  function tokenNorm(raw) {
    return lang === "ko" ? tokenJamo(raw) : tokenLatin(raw);
  }
  var ONES = [
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen"
  ];
  var TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];
  function belowHundred(n) {
    if (n < 20) return ONES[n];
    const t = TENS[Math.floor(n / 10)];
    const r = n % 10;
    return r ? t + ONES[r] : t;
  }
  function belowThousand(n) {
    const h = Math.floor(n / 100);
    const r = n % 100;
    let s = h ? ONES[h] + "hundred" : "";
    if (r) s += belowHundred(r);
    return s || "zero";
  }
  function numberWords(digits) {
    const n = parseInt(digits, 10);
    if (isNaN(n)) return digits;
    if (digits.length === 4 && n >= 1100 && n <= 2999 && n % 100 !== 0) {
      return belowHundred(Math.floor(n / 100)) + belowHundred(n % 100);
    }
    if (n < 100) return belowHundred(n);
    if (n < 1e3) return belowThousand(n);
    if (n < 1e6) {
      const th = belowThousand(Math.floor(n / 1e3)) + "thousand";
      const r = n % 1e3;
      return r ? th + belowThousand(r) : th;
    }
    if (n < 1e9) {
      const m = belowThousand(Math.floor(n / 1e6)) + "million";
      const r = n % 1e6;
      return r ? m + numberWords(String(r)) : m;
    }
    return digits;
  }
  var ORDINAL_MAP = {
    one: "first",
    two: "second",
    three: "third",
    five: "fifth",
    eight: "eighth",
    nine: "ninth",
    twelve: "twelfth"
  };
  function tokenLatin(raw) {
    let s = raw.toLowerCase();
    s = s.normalize("NFD").replace(/[̀-ͯ]/g, "").normalize("NFC");
    s = s.replace(/%/g, " percent ").replace(/&/g, " and ").replace(/\+/g, " plus ");
    s = s.replace(/(\d+)(st|nd|rd|th)\b/g, (_m, d) => {
      const w = numberWords(d);
      for (const [card, ord] of Object.entries(ORDINAL_MAP)) {
        if (w.endsWith(card)) return w.slice(0, w.length - card.length) + ord;
      }
      if (w.endsWith("y")) return w.slice(0, -1) + "ieth";
      return w + "th";
    });
    s = s.replace(/\d+/g, (d) => numberWords(d));
    s = s.replace(/[^a-z가-힣]/g, "");
    return toJamo(s);
  }

  // script.ts
  var WS = /(\s+)/;
  function prepareScript(text) {
    const tokens = [];
    let jamo = "";
    const jamoToTokenArr = [];
    let pendingBreak = false;
    const parts = text.split(WS);
    for (const part of parts) {
      if (part === "") continue;
      if (/^\s+$/.test(part)) {
        if (part.includes("\n")) pendingBreak = true;
        continue;
      }
      const j = tokenNorm(part);
      const tokenIdx = tokens.length;
      const jamoStart = jamo.length;
      tokens.push({ raw: part, jamo: j, breakBefore: pendingBreak, jamoStart });
      for (let k = 0; k < j.length; k++) jamoToTokenArr.push(tokenIdx);
      jamo += j;
      pendingBreak = false;
    }
    const index = /* @__PURE__ */ new Map();
    tokens.forEach((t, i) => {
      if (t.jamo.length < 2) return;
      const arr = index.get(t.jamo);
      if (arr) arr.push(i);
      else index.set(t.jamo, [i]);
    });
    return { tokens, jamo, jamoToToken: Int32Array.from(jamoToTokenArr), index };
  }

  // align.ts
  var DEFAULT_CONFIG = {
    back: 26,
    forwardBase: 96,
    accept: 0.5,
    reseekAccept: 0.42,
    lostThreshold: 3,
    maxQueryJamo: 60,
    minQueryJamo: 4,
    maxReseekCandidates: 48,
    backwardPenalty: 0.3,
    forwardPenalty: 0.05
  };
  function fuzzyMatchEnd(query, text, cursorInWindow, backwardPenalty, forwardPenalty) {
    const m = query.length;
    const n = text.length;
    if (m === 0 || n === 0) return { consumed: 0, dist: m };
    let prev = new Int32Array(n + 1);
    let cur = new Int32Array(n + 1);
    for (let i = 1; i <= m; i++) {
      cur[0] = i;
      const qc = query.charCodeAt(i - 1);
      for (let j = 1; j <= n; j++) {
        const sub = prev[j - 1] + (qc === text.charCodeAt(j - 1) ? 0 : 1);
        const delQ = prev[j] + 1;
        const insT = cur[j - 1] + 1;
        cur[j] = sub < delQ ? sub < insT ? sub : insT : delQ < insT ? delQ : insT;
      }
      const t = prev;
      prev = cur;
      cur = t;
    }
    let bestJ = 0;
    let bestScore = Infinity;
    let bestDist = 0;
    const biased = cursorInWindow >= 0;
    for (let j = 1; j <= n; j++) {
      const behind = cursorInWindow - j;
      let penalty = 0;
      if (biased) penalty = behind > 0 ? behind * backwardPenalty : -behind * forwardPenalty;
      const score = prev[j] + penalty;
      if (score < bestScore || score === bestScore && j > bestJ) {
        bestScore = score;
        bestJ = j;
        bestDist = prev[j];
      }
    }
    return { consumed: bestJ, dist: bestDist };
  }
  var Aligner = class {
    constructor(script, cfg = {}) {
      __publicField(this, "script");
      __publicField(this, "cfg");
      __publicField(this, "cursorJamo", 0);
      __publicField(this, "confirmedToken", 0);
      __publicField(this, "tokensPerSec", 2.5);
      // rough Korean reading default; refined as we go
      __publicField(this, "lostCount", 0);
      __publicField(this, "lastMoveTime", 0);
      __publicField(this, "lastMoveToken", 0);
      this.script = script;
      this.cfg = { ...DEFAULT_CONFIG, ...cfg };
    }
    reset(startToken = 0) {
      this.confirmedToken = startToken;
      this.cursorJamo = this.script.tokens[startToken]?.jamoStart ?? 0;
      this.lostCount = 0;
      this.lastMoveTime = 0;
      this.tokensPerSec = 2.5;
    }
    /** Jump the cursor to a specific token (manual click / voice command). */
    seekToken(token) {
      const t = Math.max(0, Math.min(this.script.tokens.length - 1, token));
      this.confirmedToken = t;
      this.cursorJamo = this.script.tokens[t]?.jamoStart ?? 0;
      this.lostCount = 0;
    }
    /** Build the jamo query from the most recent spoken words (bounded length). */
    buildQuery(spokenTokens) {
      let q = "";
      for (let i = spokenTokens.length - 1; i >= 0; i--) {
        const j = tokenNorm(spokenTokens[i]);
        if (j === "") continue;
        q = j + q;
        if (q.length >= this.cfg.maxQueryJamo) {
          q = q.slice(q.length - this.cfg.maxQueryJamo);
          break;
        }
      }
      return q;
    }
    push(spokenTokens, now) {
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
      const accept = query.length < 12 ? this.cfg.accept * 0.7 : this.cfg.accept;
      if (consumed > 0 && norm <= accept) {
        const absEnd = start + consumed;
        const token = jamoToToken[Math.min(absEnd - 1, jamoToToken.length - 1)];
        if (token < this.confirmedToken && norm > this.cfg.reseekAccept) {
          this.lostCount = 0;
          return { token: this.confirmedToken, moved: false, lost: false, confidence: 1 - norm };
        }
        this.accept(token, absEnd, now);
        return { token, moved: true, lost: false, confidence: 1 - norm };
      }
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
        confidence: Math.max(0, 1 - norm)
      };
    }
    accept(token, absEnd, now) {
      this.confirmedToken = token;
      this.cursorJamo = absEnd;
      this.lostCount = 0;
      if (this.lastMoveTime > 0) {
        const dt = (now - this.lastMoveTime) / 1e3;
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
    reseek(query, spokenTokens) {
      const { jamo, jamoToToken, index } = this.script;
      const candidates = [];
      for (const raw of spokenTokens) {
        const j = tokenNorm(raw);
        if (j.length < 2) continue;
        const positions = index.get(j);
        if (positions) for (const p of positions) candidates.push(p);
        if (candidates.length >= this.cfg.maxReseekCandidates) break;
      }
      if (candidates.length === 0) return null;
      let best = null;
      const seen = /* @__PURE__ */ new Set();
      for (const tok of candidates) {
        const anchor = this.script.tokens[tok]?.jamoStart ?? 0;
        const start = Math.max(0, anchor - query.length);
        const bucket = start >> 5;
        if (seen.has(bucket)) continue;
        seen.add(bucket);
        const end = Math.min(jamo.length, anchor + query.length + 32);
        const window = jamo.slice(start, end);
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
  };

  // glue.ts
  var prepared = null;
  var aligner = null;
  var api = {
    /**
     * Load a script. Returns display tokens as JSON: [{raw, breakBefore}].
     * cfgJson: optional Partial<AlignConfig> overrides — lets the app tune the
     * aligner (e.g. for SFSpeechRecognizer's noisier partials) without touching
     * the shared engine.
     */
    load(text, cfgJson, lang2) {
      setLang(lang2 === "en" ? "en" : "ko");
      const cfg = cfgJson ? JSON.parse(cfgJson) : {};
      prepared = prepareScript(text);
      aligner = new Aligner(prepared, cfg);
      aligner.reset(0);
      return JSON.stringify(prepared.tokens.map((t) => ({ raw: t.raw, breakBefore: t.breakBefore })));
    },
    /** Push the latest spoken words (JSON array). Returns AlignResult JSON. */
    push(wordsJson, nowMs) {
      if (!aligner) return "null";
      const words = JSON.parse(wordsJson);
      const r = aligner.push(words, nowMs);
      return JSON.stringify({ ...r, tokensPerSec: aligner.tokensPerSec });
    },
    /** Manual jump (tap on a word). */
    seek(token) {
      aligner?.seekToken(token);
    },
    reset(startToken) {
      aligner?.reset(startToken);
    }
  };
  globalThis.PromptEngine = api;
})();
