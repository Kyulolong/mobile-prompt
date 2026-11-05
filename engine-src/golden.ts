/**
 * Regenerates android/app/src/test/resources/engine-golden.json in place:
 * keeps every recorded INPUT (the normalization samples, the scripts, the real
 * recognizer transcripts in each alignment scenario) and rewrites the expected
 * OUTPUTS from the current TypeScript engine. Both the Kotlin port
 * (EngineGoldenTest.kt) and the iOS bridge (AlignEngineGoldenTests.swift) are
 * pinned to this file.
 *
 *   npx -y tsx engine-src/golden.ts
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { prepareScript } from "./script";
import { Aligner, type AlignConfig } from "./align";
import { setLang, tokenNorm, type EngineLang } from "./norm";
import { tokenJamo } from "./jamo";

const here = dirname(fileURLToPath(import.meta.url));
const file = resolve(here, "../android/app/src/test/resources/engine-golden.json");
const golden = JSON.parse(readFileSync(file, "utf8"));

for (const sample of golden.norm) {
  setLang("ko");
  sample.ko = tokenJamo(sample.raw);
  setLang("en");
  sample.en = tokenNorm(sample.raw);
}

for (const script of golden.scripts) {
  setLang(script.lang as EngineLang);
  const p = prepareScript(script.text);
  script.jamoLength = p.jamo.length;
  script.indexSize = p.index.size;
  script.tokens = p.tokens.map((t) => ({ raw: t.raw, jamo: t.jamo, breakBefore: t.breakBefore, jamoStart: t.jamoStart }));
}

for (const scenario of golden.align) {
  setLang(scenario.lang as EngineLang);
  const a = new Aligner(prepareScript(scenario.text), scenario.cfg as Partial<AlignConfig>);
  a.reset(0);
  for (const step of scenario.steps) {
    if (step.seek !== undefined) a.seekToken(step.seek);
    if (step.reset !== undefined) a.reset(step.reset);
    const r = a.push(step.words, step.now);
    step.result = {
      token: r.token, moved: r.moved, lost: r.lost, confidence: r.confidence,
      tokensPerSec: a.tokensPerSec, cursorJamo: a.cursorJamo, confirmedToken: a.confirmedToken,
    };
  }
}

writeFileSync(file, JSON.stringify(golden)); // minified, as recorded
console.log(`OK: ${golden.norm.length} norm, ${golden.scripts.length} scripts, ${golden.align.length} scenarios → ${file}`);
