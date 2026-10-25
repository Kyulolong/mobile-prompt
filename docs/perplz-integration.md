# 퍼플즈 ↔ 보이스 프롬프터 연동 스펙

퍼플즈 앱(RN/Flutter)과 보이스 프롬프터(iOS·Android 네이티브, 발행사 Team Inpact Inc.) 사이의 계약이다.
프롬프터는 계정·서버·분석이 없다. 대본은 딥링크로 기기 안에서만 오가고, 영상 업로드는 사용자가
OS 공유 시트에서 퍼플즈를 골랐을 때만 일어난다.

## 1. 사용자 흐름

1. 프로젝트룸 → 프로젝트 종료(팀 투표 완료) 또는 데모데이 전 → **"프로젝트 소개 영상 만들기"**
2. 퍼플즈가 미션·로드맵·내 역할·기여·결과에서 60~90초 대본을 생성(AI 프로젝트 생성 기능 재사용) → 사용자 편집
3. **"프롬프터로 열기"** → 프롬프터가 열리고 대본이 라이브러리에 저장됨 → `autostart=1`이면 바로 촬영 화면
4. 사용자가 읽으며 녹화 → 저장 알림에서 **"영상·자막 공유"** → 공유 시트에서 퍼플즈 선택 → 퍼플즈가 mp4+srt를 받아 프로젝트와 프로필에 첨부
5. **"퍼플즈로 돌아가기"**(`return` 지정 시) → 퍼플즈의 해당 프로젝트 화면으로 복귀

## 2. 퍼플즈 → 프롬프터: 대본 넘기기

```
voiceprompter://script?s=<percent-encoded 대본>&autostart=1&return=<percent-encoded URL>
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `s` | 예 | 대본 전문. `encodeURIComponent`로 인코딩. UTF-8 16 KB 이하. 비어 있으면 무시됨 |
| `autostart` | 아니오 | `1`이면 편집 화면을 건너뛰고 바로 프롬프터(촬영) 화면. 없으면 편집 화면에서 멈춤 |
| `return` | 아니오 | 녹화 저장 후 "퍼플즈로 돌아가기" 버튼이 여는 URL. `perplz://` 또는 `https://perplz.com/`으로 시작해야 하며 그 외는 버려짐 |

- 줄바꿈은 그대로 인코딩(`%0A`). 빈 줄은 단락 구분으로 표시된다.
- 인식 언어는 대본에서 자동 감지된다(한국어·영어·혼합).
- 16 KB를 넘는 대본은 OS 공유 시트(`text/plain`)로 보낸다. 프롬프터는 양쪽 플랫폼 모두 공유 수신을 지원하며 새 대본으로 저장된다(이 경우 autostart·return 없음).
- 대본은 프롬프터의 라이브러리에 남는다. 같은 대본을 두 번 보내면 두 개가 생긴다.

### 미설치 폴백 (퍼플즈 책임)

- React Native: `Linking.canOpenURL(url)` → false면 스토어로. iOS는 `Info.plist`의 `LSApplicationQueriesSchemes`에 `voiceprompter`를 넣어야 `canOpenURL`이 true를 돌려준다.
- Flutter: `url_launcher`의 `canLaunchUrl` → 같은 iOS 설정 필요.
- 스토어: App Store `https://apps.apple.com/app/id6793995765`, Play `https://play.google.com/store/apps/details?id=com.teaminpact.mobileprompt`
- 2단계에서 `https://perplz.com/prompter/open?...` 유니버설·앱 링크로 바꾸면 미설치 시 그 주소가 브라우저에서 열려 스토어 버튼이 있는 랜딩으로 쓸 수 있다. perplz.com에 `apple-app-site-association`과 `.well-known/assetlinks.json` 호스팅 필요(팀 ID N7NLN3LS82, 패키지 com.teaminpact.mobileprompt).

### 예시 (RN)

```ts
const url = `voiceprompter://script?s=${encodeURIComponent(script)}&autostart=1&return=${encodeURIComponent(`perplz://project/${projectId}/video`)}`;
if (await Linking.canOpenURL(url)) await Linking.openURL(url);
else Linking.openURL(Platform.OS === 'ios' ? APP_STORE_URL : PLAY_URL);
```

## 3. 프롬프터 → 퍼플즈: 영상·자막 돌려주기

프롬프터는 저장 알림의 "영상·자막 공유"로 OS 공유 시트를 연다.

| 항목 | iOS | Android |
|---|---|---|
| 영상 | `.mov` 파일 URL (H.264/AAC, 1080p까지) | `content://media/...` MediaStore URI (`video/mp4`) |
| 자막 | `.srt` 파일 URL (음성 따라가기로 녹화했을 때만) | FileProvider URI (`application/x-subrip`) |
| 인텐트 | `UIActivityViewController` 항목 2개 | `ACTION_SEND_MULTIPLE`, `*/*`, `EXTRA_STREAM`에 URI 목록 |

퍼플즈 앱이 할 일:
- 공유 수신 타깃 등록: `video/*`와 `text/*`(또는 `*/*`) — RN `react-native-receive-sharing-intent` 류, Flutter `receive_sharing_intent`.
- 받은 영상·자막을 프로젝트에 첨부하고 프로필·포트폴리오에 노출. 자막 파일명은 `VoicePrompter-YYYYMMDD-HHmmss.srt`, 영상도 같은 타임스탬프.
- `return`으로 돌아왔을 때 방금 첨부한 영상이 보이도록 화면 상태를 맞춘다.

## 4. 측정 (퍼플즈 쪽 이벤트)

대본 생성 수 · 프롬프터 열기 수 · 미설치 폴백 수 · 영상 첨부 수 · 프로젝트당 영상 비율. 프롬프터는 아무것도 수집하지 않는다.

## 5. 검증

```bash
# iOS 시뮬레이터
xcrun simctl openurl booted "voiceprompter://script?s=%EC%95%88%EB%85%95%ED%95%98%EC%84%B8%EC%9A%94&autostart=1&return=perplz%3A%2F%2Fproject%2F1"
# Android
adb shell "am start -a android.intent.action.VIEW -d 'voiceprompter://script?s=%EC%95%88%EB%85%95%ED%95%98%EC%84%B8%EC%9A%94&autostart=1&return=perplz%3A%2F%2Fproject%2F1'"
```

기대 결과: 라이브러리에 새 대본, 프롬프터 화면 즉시 진입, 녹화 저장 알림에 "영상·자막 공유"와 "퍼플즈로 돌아가기".
