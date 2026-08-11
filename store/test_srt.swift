// Standalone check of the SRT cue algorithm (mirrors PrompterViewModel.finishSRT).
import Foundation

struct Tok { let raw: String; let breakBefore: Bool }
let tokens = "안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.|시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요."
    .split(separator: "|").enumerated().flatMap { (pi, para) in
        para.split(separator: " ").enumerated().map { (i, w) in Tok(raw: String(w), breakBefore: pi > 0 && i == 0) }
    }

// 가짜 녹화: 시작 100.0초, 단어당 0.4초씩 전진, 토큰 20에서 종료
let recStart = 100.0
var recWordTimes: [(token: Int, t: Double)] = []
for i in 0...20 { recWordTimes.append((i, 100.8 + Double(i) * 0.4)) }

var tokenTime = [Double?](repeating: nil, count: tokens.count)
var filled = -1
for e in recWordTimes where e.token > filled {
    for j in (filled + 1)...min(e.token, tokens.count - 1) where tokenTime[j] == nil { tokenTime[j] = e.t }
    filled = max(filled, e.token)
}

var cues: [(start: Double, end: Double, text: String)] = []
var rangeStart = 0
var charCount = 0
for i in 0..<tokens.count {
    let raw = tokens[i].raw
    let endsSentence = raw.hasSuffix(".") || raw.hasSuffix("!") || raw.hasSuffix("?") || raw.hasSuffix("…")
    let nextBreaks = i + 1 < tokens.count ? tokens[i + 1].breakBefore : true
    charCount += raw.count + 1
    let overBudget = charCount >= 26
    let commaBreak = raw.hasSuffix(",") && charCount >= 14
    guard endsSentence || nextBreaks || overBudget || commaBreak || i == tokens.count - 1 else { continue }
    defer { rangeStart = i + 1; charCount = 0 }
    let range = rangeStart...i
    let times = range.compactMap { tokenTime[$0] }
    guard let first = times.first, let last = times.last else { continue }
    let start = max(0, first - recStart - 0.25)
    let end = max(last - recStart + 0.8, start + 0.8)
    cues.append((start, end, range.map { tokens[$0].raw }.joined(separator: " ")))
}
for i in 1..<cues.count where cues[i].start < cues[i - 1].end {
    cues[i].start = cues[i - 1].end + 0.01
    cues[i].end = max(cues[i].end, cues[i].start + 0.5)
}
func stamp(_ s: Double) -> String {
    let ms = Int((s * 1000).rounded())
    return String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
for (i, c) in cues.enumerated() { print("\(i + 1)\n\(stamp(c.start)) --> \(stamp(c.end))\n\(c.text)\n") }
