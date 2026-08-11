/**
 * Turns a raw script string into the structures the aligner needs:
 *  - an ordered list of display tokens (eojeol)
 *  - one big jamo stream with a jamo-index -> token-index map
 *  - an inverted index of distinctive tokens for "I'm lost" re-seeking
 *
 * Works the same whether the script is 10 seconds or 10 minutes: the aligner
 * only ever scans a bounded window of the jamo stream (see engine/align.ts).
 */
import { tokenNorm as tokenJamo } from "./norm";

export interface ScriptToken {
  /** original text for display (keeps punctuation/case) */
  raw: string;
  /** normalized jamo used for matching (may be empty for pure emoji/punct) */
  jamo: string;
  /** true if a line break precedes this token in the source */
  breakBefore: boolean;
  /** start offset of this token inside PreparedScript.jamo */
  jamoStart: number;
}

export interface PreparedScript {
  tokens: ScriptToken[];
  /** concatenation of every token's jamo, no separators */
  jamo: string;
  /** jamoToToken[i] = index of the token that jamo char i belongs to */
  jamoToToken: Int32Array;
  /** canonical token jamo -> token indices where it appears */
  index: Map<string, number[]>;
}

const WS = /(\s+)/;

export function prepareScript(text: string): PreparedScript {
  const tokens: ScriptToken[] = [];
  let jamo = "";
  const jamoToTokenArr: number[] = [];
  let pendingBreak = false;

  const parts = text.split(WS);
  for (const part of parts) {
    if (part === "") continue;
    if (/^\s+$/.test(part)) {
      if (part.includes("\n")) pendingBreak = true;
      continue;
    }
    const j = tokenJamo(part);
    const tokenIdx = tokens.length;
    const jamoStart = jamo.length;
    tokens.push({ raw: part, jamo: j, breakBefore: pendingBreak, jamoStart });
    for (let k = 0; k < j.length; k++) jamoToTokenArr.push(tokenIdx);
    jamo += j;
    pendingBreak = false;
  }

  // Inverted index for re-seek. Only reasonably distinctive tokens (>= 2 jamo)
  // are worth indexing as anchors.
  const index = new Map<string, number[]>();
  tokens.forEach((t, i) => {
    if (t.jamo.length < 2) return;
    const arr = index.get(t.jamo);
    if (arr) arr.push(i);
    else index.set(t.jamo, [i]);
  });

  return { tokens, jamo, jamoToToken: Int32Array.from(jamoToTokenArr), index };
}
