# 1.4.0 스토어 자료 (iOS · Android)

> 고도화 릴리즈. 포지셔닝("퍼플즈가 만든, 목소리를 따라오는 프롬프터")·발행사·아이콘·개인정보처리방침은 1.3 그대로.
> 부제목·키워드·설명은 1.3.0(`store/listing-1.3.md`)을 유지하고 아래 설명 추가분과 릴리스 노트만 반영한다.
> 스크린샷은 1.3 그대로 써도 되지만, 무대 모드·연습 결과 화면 한 장씩을 더하면 좋다.
> ⚠️ 플로팅(PiP)은 실기기 검증(iPhone 12 mini·Galaxy S21)이 끝난 뒤에만 문구에 남긴다. 검증에서 음성 따라가기가
> 백그라운드에서 끊기면 "자동 스크롤로 띄울 수 있어요"로 낮추고, 아예 못 쓰면 플로팅 줄을 뺀다.

## 한국어 (ko)

### 설명에 더하는 항목

"크리에이터를 위해" 아래에:

```
• 무대 모드 — 카메라 없이 대본만 크게. 연설·강의, 다른 기기로 하는 Zoom·라이브에
• 플로팅 — 무대 모드의 대본을 작은 창(PiP)으로 띄워 다른 앱 위에서 읽어요
• 연습 모드 — 녹화 없이 읽고 총 시간·속도·멈춘 곳·다시 읽은 문장을 돌려받아요
• 대본에 **강조**와 [메모] 표시, 글자수·예상 소요 시간
• 텍스트 파일 가져오기, 대본 검색·복제
• 4K 촬영, 볼륨 버튼으로 녹화 시작·정지
```

### 이번 버전의 새로운 기능 (1.4.0)

```
• 무대 모드 — 카메라 없이 대본만 크게. 연설·강의, 다른 기기로 하는 Zoom·라이브에 쓰세요. 음성 따라가기와 자동 스크롤은 그대로예요.
• 플로팅 — 무대 모드에서 대본을 작은 창(PiP)으로 띄우고 다른 앱을 쓰세요. 창의 재생/일시정지로 프롬프터를 멈추고 이어갑니다. 다른 앱이 마이크를 쓰면 자동 스크롤로 이어가요.
• 연습 모드 — 녹화 없이 읽어 보고 총 시간, 읽기 속도, 멈춘 곳, 다시 읽은 문장을 확인하세요. 녹화 뒤에도 "이번 테이크 결과"로 볼 수 있어요.
• 대본에 **강조**(굵게)와 [메모](흐리게, 읽지 않음) 표시를 쓸 수 있고, 편집기에 글자수와 예상 소요 시간이 보여요. 한 번 연습하면 내 속도로 계산해요.
• 텍스트 파일(.txt/.md) 가져오기, 대본 검색, 복제.
• 촬영 옵션: 4K, 고효율 비디오(HEVC, iOS), 볼륨 버튼으로 녹화 시작·정지.
• 고친 것: 긴 녹화 중 화면이 꺼지던 문제, 녹화 중 닫으면 공유 알림이 사라지던 문제, 마이크 권한을 거부하면 자동 스크롤로 넘어갈 수 없던 문제, 카메라 권한 거부 안내, 영상·자막 파일 이름 통일.
```

## English (U.S.)

### Added to the description

Under "MADE FOR CREATORS":

```
• Stage Mode — script only, no camera, bigger text. For speeches, lectures, and Zoom or live streams on another device
• Floating — pop the Stage Mode script out into a small picture-in-picture window over other apps
• Rehearsal — read without recording and get your time, pace, pauses, and the sentences you re-read
• **Emphasis** and [notes] in scripts, character counts, and a reading-time estimate
• Import text files, search and duplicate scripts
• 4K recording, volume buttons start and stop recording
```

### What's New (1.4.0)

```
• Stage Mode — script only, no camera, bigger text. For speeches, lectures, and Zoom or live streams on another device. Voice-follow and auto-scroll work as before.
• Floating — in Stage Mode, pop the script out into a small picture-in-picture window and use other apps. The window's play/pause pauses the prompter; if another app takes the microphone, the prompter continues with auto-scroll.
• Rehearsal — read without recording and see your total time, pace, pauses, and the sentences you re-read. After a recording, "This Take's Results" shows the same.
• Scripts understand **emphasis** (bold) and [notes] (dimmed, never spoken); the editor shows character counts and a reading-time estimate that switches to your own pace after one rehearsal.
• Import text files (.txt/.md), search scripts, duplicate scripts.
• Recording options: 4K, high-efficiency video (HEVC, iOS), volume buttons to start and stop recording.
• Fixed: the screen could lock during a long take; closing while recording lost the share prompt; denying the microphone left no way to switch to auto-scroll; camera-permission guidance; matching names for the video and its subtitles.
```

## 심사 노트 (iOS, 1.3 노트에 덧붙임)

```
무대 모드에서는 카메라를 쓰지 않고 마이크로 온디바이스 음성인식만 합니다. audio 백그라운드 모드는 사용자가
버튼으로 시작한 Picture-in-Picture 창(플로팅)이 떠 있는 동안 인식을 이어가기 위해서만 쓰이며, PiP는 사용자가
시작해야만 열립니다. 서버 통신·계정은 여전히 없습니다.
```

## Play Console

- 새 선언 없음: 포그라운드 서비스·오버레이 권한을 쓰지 않는다(PiP는 액티비티 기능). 데이터 보안 답은 그대로.
- 출시 노트는 위 한국어/영어 "새로운 기능"을 그대로.
