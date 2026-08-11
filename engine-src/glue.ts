/**
 * JavaScriptCore glue for the iOS app.
 *
 * The alignment engine (jamo/script/align) is used verbatim from the web app;
 * this file is the only mobile-specific JS. Swift talks to `PromptEngine`
 * with JSON strings — all heavy structures (jamo stream, inverted index,
 * Int32Array) stay on the JS side.
 */
import { prepareScript, type PreparedScript } from "./script";
import { Aligner, type AlignConfig } from "./align";
import { setLang, type EngineLang } from "./norm";

let prepared: PreparedScript | null = null;
let aligner: Aligner | null = null;

const api = {
  /**
   * Load a script. Returns display tokens as JSON: [{raw, breakBefore}].
   * cfgJson: optional Partial<AlignConfig> overrides — lets the app tune the
   * aligner (e.g. for SFSpeechRecognizer's noisier partials) without touching
   * the shared engine.
   */
  load(text: string, cfgJson?: string, lang?: string): string {
    setLang(lang === "en" ? "en" : "ko");
    const cfg: Partial<AlignConfig> = cfgJson ? JSON.parse(cfgJson) : {};
    prepared = prepareScript(text);
    aligner = new Aligner(prepared, cfg);
    aligner.reset(0);
    return JSON.stringify(prepared.tokens.map((t) => ({ raw: t.raw, breakBefore: t.breakBefore })));
  },

  /** Push the latest spoken words (JSON array). Returns AlignResult JSON. */
  push(wordsJson: string, nowMs: number): string {
    if (!aligner) return "null";
    const words: string[] = JSON.parse(wordsJson);
    const r = aligner.push(words, nowMs);
    return JSON.stringify({ ...r, tokensPerSec: aligner.tokensPerSec });
  },

  /** Manual jump (tap on a word). */
  seek(token: number): void {
    aligner?.seekToken(token);
  },

  reset(startToken: number): void {
    aligner?.reset(startToken);
  },
};

(globalThis as Record<string, unknown>).PromptEngine = api;
