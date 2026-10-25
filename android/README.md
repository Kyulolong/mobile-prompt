# MobilePrompt for Android (보이스 프롬프터)

iOS 앱([../README.md](../README.md))의 안드로이드 판. Kotlin + Jetpack Compose, CameraX, 그리고 온디바이스 음성인식
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)로 만들었다. 자모 정렬 엔진은 `engine-src/`의 TypeScript를
Kotlin으로 1:1 포팅했고, 골든 테스트가 TS 엔진 출력과 단계별로 일치함을 보장한다.

```
mic ─ AudioRecord 16 kHz ─▶ sherpa-onnx OnlineRecognizer (ko / en, zipformer int8)   ← speech/SpeechEngine.kt
                                   │ 최근 단어 8개 (0.2 s 스로틀)
                                   ▼
                          engine/Align.kt (= align.ts)   ← 토큰 index + 읽기 속도
                                   ▼
                          engine/ScrollModel.kt (Choreographer: creep + spring)
                                   ▼
                          ui/PromptTextView.kt (TextView + Span 하이라이트, 토큰별 줄 좌표)

CameraX Preview + VideoCapture ─▶ MediaStore (Movies/Voice Prompter/*.mp4)   ← capture/CaptureController.kt
```

iOS와 달리 녹화와 음성인식이 마이크를 공유하지 않는다. 안드로이드는 한 앱이 캡처 클라이언트를 여럿 열 수 있어
CameraX 녹음(CAMCORDER)과 인식용 AudioRecord(VOICE_RECOGNITION)가 동시에 돈다.

## 빌드

요구: Android Studio(JBR 포함)만 있으면 된다. 명령줄에서는 `JAVA_HOME`을 Android Studio의 JBR로 잡는다.

```bash
cd android
./scripts/fetch-models.sh          # sherpa-onnx AAR(49 MB) + 음성 모델(~180 MB) 다운로드, git에 없음 — 처음 한 번
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest    # 엔진 골든 테스트
./gradlew :app:bundleDebug          # 디버그 AAB (에셋팩 포함)
```

**디버그 APK(`assembleDebug`)에는 모델이 들어가지 않는다.** 모델은 `:speechmodels` install-time 에셋팩에 있어
AAB에만 포함된다. 기기/에뮬레이터에 올릴 때는 Android Studio의 Run을 쓰거나(에셋팩을 함께 배포한다), bundletool을 쓴다:

```bash
java -jar bundletool.jar build-apks --bundle=app/build/outputs/bundle/debug/app-debug.aab \
     --output=/tmp/app.apks --connected-device
java -jar bundletool.jar install-apks --apks=/tmp/app.apks
```

### 마이크 없이 인식·정렬 검증 (디버그 전용)

`app/src/debug/assets/selftest/{ko,en}.wav`는 macOS `say`로 읽은 샘플 대본이다. 설치 후:

```bash
adb shell am start -n com.teaminpact.mobileprompt.debug/com.teaminpact.mobileprompt.MainActivity --es selftest ko
adb logcat -s SelfTest
```

`RESULT lang=ko tokens=120 lastToken=… rtf=…` 줄에서 마지막 토큰이 대본 끝에 가까운지, rtf(처리시간/오디오시간)가 1보다
충분히 작은지 본다.

## 릴리즈

1. `android/keystore/upload.jks` + `android/keystore.properties`가 있어야 한다(둘 다 git 제외). 업로드 키는 Play App Signing의
   업로드 키로만 쓰이므로 분실 시 Play Console에서 재설정할 수 있다.
2. `app/build.gradle.kts`의 `versionCode`/`versionName`을 올린다.
3. `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
4. Play Console → 앱 → 프로덕션(또는 테스트 트랙) → AAB 업로드. 에셋팩은 AAB 안에 들어 있어 별도 작업이 없다.

## 튜닝 노브

- 정렬 민감도: `ui/PrompterViewModel.kt`의 `alignOverrides` (iOS와 같은 값으로 시작)
- 인식 엔드포인트·스레드 수: `speech/SpeechEngine.kt`의 `SpeechModels`
- 스크롤 느낌: `engine/ScrollModel.kt` 상단 프로퍼티 (dp 단위 = iOS 포인트)
- 모델 교체: `scripts/fetch-models.sh`의 모델 이름과 `SpeechModels`의 파일명

## 디렉터리

```
app/src/main/java/com/teaminpact/mobileprompt/
  engine/    Jamo, Norm, Script, Align, PromptEngine (TS 포팅), ScrollModel, Srt
  speech/    SpeechEngine (sherpa-onnx + AudioRecord)
  capture/   CaptureController (CameraX)
  model/     AppSettings, ScriptStore
  ui/        AppRoot, LibraryScreen, EditorScreen, PrompterScreen, PrompterViewModel, PromptTextView, SettingsSheet
app/src/test/resources/engine-golden.json   TS 엔진에서 생성한 골든 데이터
speechmodels/                                install-time 에셋팩 (모델 파일은 fetch-models.sh)
```
