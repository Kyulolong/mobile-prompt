# MobilePrompt

[auto-prompt](https://github.com/Kyulolong/auto-prompt)(보이스 프롬프터 웹앱)의 iOS 버전.
**셀피 카메라 화면 위에 반투명 대본을 띄우고**, 내 목소리를 들으며 지금 읽는 위치를 실시간으로 따라간다. 앱 안에서 영상 녹화까지 — 결과 영상에는 글자가 찍히지 않고, 갤러리에 저장된다.

- **음성 따라가기** — Apple 온디바이스 음성인식(ko-KR)으로 대본 정렬. 웹 버전의 자모 정렬 엔진을 그대로 사용
- **자동 스크롤** — 마이크 없이 일정 속도, 속도 슬라이더 + 일시정지
- **녹화** — 카메라 프리뷰 + 대본 오버레이를 보며 촬영, 영상은 깨끗하게 갤러리로
- **단어 탭** — 대본의 아무 단어나 탭하면 그 위치로 점프

## 아키텍처

```
                    ┌─ SwiftUI (Editor / Prompter / Settings)
                    │
mic ─ AVCaptureSession ─┬─ AVCaptureVideoPreviewLayer  (셀피 프리뷰)
      (전면 카메라+마이크) ├─ AVAssetWriter               (mov 녹화 → 갤러리)
                    └─ SFSpeechRecognizer (ko-KR)   (같은 마이크 버퍼를 공유)
                              │ 최근 단어들
                              ▼
                    JavaScriptCore ─ engine.js      (★ 웹 버전의 정렬 엔진 그대로)
                              │ 현재 token index + 읽기 속도
                              ▼
                    ScrollModel (CADisplayLink)     (scroll.ts의 Swift 포팅: creep + spring)
                              ▼
                    UITextView (TextKit)            (토큰별 좌표 + 현재 단어 하이라이트)
```

핵심 설계: **하나의 AVCaptureSession**이 마이크 오디오 버퍼를 녹화(AVAssetWriter)와 음성인식(SFSpeechRecognizer)에 동시에 나눠준다. iOS에서 "녹화하면서 음성 따라가기"가 되는 이유.

자모 정렬 엔진(`engine-src/jamo.ts`, `script.ts`, `align.ts`)은 auto_prompt에서 **수정 없이 복사**한 것. esbuild로 번들해(`engine-src/build.sh` → `MobilePrompt/Engine/engine.js`) iOS 내장 JavaScriptCore에서 돌린다. 포팅 드리프트 없음. 번들 산출물은 커밋되므로 Xcode 빌드에 node가 필요 없다.

## 실행

### 시뮬레이터 (UI·정렬·자동 스크롤 확인용)

```bash
xcodebuild -project MobilePrompt.xcodeproj -scheme MobilePrompt \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

시뮬레이터에는 카메라가 없어 프리뷰는 "카메라를 사용할 수 없어요"로 표시된다. 음성인식은 Mac 마이크로 동작한다.

### 실기기 (카메라·녹화 포함 전체 기능)

1. `MobilePrompt.xcodeproj`를 Xcode로 열기
2. 타겟 MobilePrompt → Signing & Capabilities → Team에 본인 Apple ID 선택
   (무료 계정 가능 — 설치 후 7일마다 재설치 필요, 유료 개발자 계정이면 1년)
3. 아이폰을 USB로 연결하고 상단 기기 목록에서 선택 → Run
4. 아이폰에서 설정 > 일반 > VPN 및 기기 관리에서 개발자 앱 신뢰

## 엔진 수정 시

auto_prompt 쪽 엔진을 고쳤다면 파일을 `engine-src/`로 다시 복사하고:

```bash
./engine-src/build.sh
```

## 튜닝 노브

- 정렬 민감도: `engine-src/align.ts`의 `DEFAULT_CONFIG` (웹 버전과 동일)
- 스크롤 느낌: [ScrollModel.swift](MobilePrompt/Engine/ScrollModel.swift) 상단 프로퍼티 (`spring`, `creepCapTokens`, `readingLineFrac`)
- 화면에서 실시간 조절: 글자 크기 · 배경 어둡기 · 읽는 줄 위치 · 거울 모드 · 자동 스크롤 속도 (설정 시트)

## 상태

MVP. 시뮬레이터에서 검증된 것: 정렬 엔진(JSC), 음성 따라가기(Mac 마이크), 자동 스크롤, 하이라이트/스크롤 추종, 설정.
실기기에서 확인 필요: 카메라 프리뷰, 녹화 + 갤러리 저장, 녹화 중 음성 따라가기(마이크 공유), 온디바이스 인식 지연·발열.
