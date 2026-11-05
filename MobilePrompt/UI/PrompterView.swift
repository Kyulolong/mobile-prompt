import SwiftUI

/// Full-screen prompter: selfie camera behind, translucent scrim, the script
/// scrolling on top, and record/mode controls.
struct PrompterView: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var vm = PrompterViewModel()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showSRTShare = false
    /// X while recording asks first; "stop and close" then waits for the
    /// save alert (share / back to Perplz) before the prompter goes away.
    @State private var confirmClose = false
    @State private var closeAfterSave = false
    let script: String
    /// Set when the script came from Perplz: offers "Back to Perplz" after saving.
    var returnURL: URL? = nil

    /// Recorded movie plus its subtitles (when voice-follow produced one).
    private var shareItems: [Any] {
        [vm.capture.lastRecordingURL, vm.srtFileURL].compactMap { $0 }
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if settings.stageMode {
                Color.black.ignoresSafeArea()
            } else {
            switch vm.capture.cameraState {
            case .ready:
                CameraPreview(controller: vm.capture)
                    .ignoresSafeArea()
            case .starting:
                VStack(spacing: 10) {
                    ProgressView()
                    Text("카메라 켜는 중…")
                        .font(.caption)
                }
                .foregroundStyle(.white.opacity(0.35))
            case .unavailable:
                if isDemoBackdrop {
                    // Simulator-only stand-in for App Store screenshots; real
                    // devices always render the live camera instead.
                    LinearGradient(colors: [Color(red: 0.36, green: 0.42, blue: 0.62),
                                            Color(red: 0.16, green: 0.19, blue: 0.33),
                                            Color(red: 0.06, green: 0.07, blue: 0.13)],
                                   startPoint: .top, endPoint: .bottom)
                        .ignoresSafeArea()
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: "video.slash")
                            .font(.largeTitle)
                        Text("이 기기에서는 카메라를 쓸 수 없어\n대본 화면만 표시돼요")
                            .font(.caption)
                            .multilineTextAlignment(.center)
                    }
                    .foregroundStyle(.white.opacity(0.25))
                }
            case .denied:
                VStack(spacing: 8) {
                    Image(systemName: "video.slash")
                        .font(.largeTitle)
                    Text("카메라 권한이 필요해요")
                        .font(.caption)
                }
                .foregroundStyle(.white.opacity(0.25))
            }
            Color.black.opacity(settings.scrimOpacity)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            }

            // In landscape the front camera sits on one side edge; a full-width
            // column makes the reader's gaze wander visibly off-lens. Narrow
            // the script and pin it to the camera's side (detected from the
            // interface orientation) so the eyes stay next to the lens.
            GeometryReader { geo in
                let landscape = geo.size.width > geo.size.height
                // Stage mode has no lens to hug: the box settings apply in
                // every orientation.
                let hugCamera = landscape && !settings.stageMode
                let boxWidth = hugCamera ? geo.size.width * 0.55
                                         : geo.size.width * settings.portraitWidthFrac
                let topPad = hugCamera ? 0 : geo.size.height * settings.portraitTopFrac
                let bottomPad = hugCamera ? 0 : geo.size.height * settings.portraitBottomFrac
                let bandHeight = geo.size.height - topPad - bottomPad

                HStack(spacing: 0) {
                    if !hugCamera || !Self.cameraOnLeadingEdge { Spacer(minLength: 0) }
                    ScriptTextView(viewModel: vm,
                                   tokens: vm.tokens,
                                   fontSize: settings.stageMode ? settings.stageFontSize : settings.fontSize,
                                   readingLineFrac: settings.readingLineFrac)
                        .padding(.horizontal, 14)
                        .scaleEffect(x: settings.mirrored ? -1 : 1, y: 1)
                        .frame(width: boxWidth)
                        .padding(.top, topPad)
                        .padding(.bottom, bottomPad)
                    if !hugCamera || Self.cameraOnLeadingEdge { Spacer(minLength: 0) }
                }

                // Reading-line marker, aligned with the (possibly inset) box.
                Rectangle()
                    .fill(Color.white.opacity(0.18))
                    .frame(height: 1)
                    .offset(y: topPad + bandHeight * settings.readingLineFrac)
                    .allowsHitTesting(false)
            }

            VStack(spacing: 10) {
                topBar
                if vm.capture.isRecording {
                    recordingBadge
                }
                if vm.rehearsalActive {
                    rehearsalBadge
                }
                if !settings.stageMode, vm.capture.cameraState == .denied {
                    cameraDeniedBanner
                }
                Spacer()
                bottomBar
            }

            if let c = vm.countdown {
                Text("\(c)")
                    .font(.system(size: 130, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.6), radius: 16, y: 4)
                    .contentTransition(.numericText(countsDown: true))
                    .animation(.snappy, value: c)
                    .allowsHitTesting(false)
            }

            if let toast = vm.toast {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.75), in: Capsule())
                        .padding(.bottom, 120)
                }
                .transition(.opacity)
                .allowsHitTesting(false)
            }

            if let msg = vm.errorMessage {
                Text(msg)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(24)
                    .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 32)
            }

            // Both overlays are about voice-follow; auto-scroll never needs them.
            if settings.voiceMode, vm.permissionState == .denied {
                permissionOverlay
            } else if settings.voiceMode, vm.speechStatus == .dictationDisabled {
                dictationOverlay
            }
        }
        .background(CaptureEventBridge(enabled: settings.hardwareShutter && !settings.stageMode && vm.capture.cameraAvailable && !showSettings,
                                       onPress: { vm.hardwareShutterPressed() }))
        .statusBarHidden()
        .task {
            await vm.start(script: script, settings: settings)
        }
        .onDisappear { vm.stopAll() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                vm.sceneDidBecomeActive()
                vm.recheckPermissions()
            case .background:
                vm.sceneDidEnterBackground()
            default:
                break
            }
        }
        .onChange(of: settings.stageMode) { _, on in vm.setStageMode(on) }
        .onChange(of: settings.voiceMode) { _, voice in vm.setMode(voice: voice) }
        .onChange(of: settings.autoSpeed) { _, v in vm.scroll.autoPxPerSec = v }
        .onChange(of: settings.readingLineFrac) { _, v in vm.scroll.readingLineFrac = v }
        .onChange(of: settings.highlightStyle) { _, v in vm.setHighlightStyle(v) }
        .onChange(of: settings.videoQuality) { _, q in vm.setVideoQuality(q) }
        .onChange(of: settings.hevc) { _, on in vm.setHEVC(on) }
        .sheet(isPresented: $showSettings) { SettingsSheet(compact: true) }
        .alert("녹화를 멈출까요?", isPresented: $confirmClose) {
            Button("멈추고 닫기", role: .destructive) {
                if vm.capture.isRecording {
                    closeAfterSave = true
                    vm.capture.stopRecording() // the save alert follows, then closeNow()
                } else {
                    closeNow()
                }
            }
            Button("계속 녹화", role: .cancel) {}
        } message: {
            Text("프롬프터가 닫혀요. 지금까지 찍은 영상은 갤러리에 저장돼요.")
        }
        .sheet(item: $vm.report, onDismiss: { if closeAfterSave { closeNow() } }) { report in
            RehearsalResultView(report: report,
                                onAgain: { vm.startRehearsal() },
                                onRecord: { vm.recordTapped() })
                .presentationDetents([.medium, .large])
        }
        .alert("안내", isPresented: saveAlertBinding) {
            if vm.takeReportAvailable {
                Button("이번 테이크 결과") {
                    vm.capture.saveMessage = nil
                    vm.showTakeReport() // closes after the sheet, if asked to
                }
            }
            if !shareItems.isEmpty {
                Button("영상·자막 공유") {
                    vm.capture.saveMessage = nil
                    showSRTShare = true // closes after the share sheet, if asked to
                }
            }
            if let returnURL {
                Button("퍼플즈로 돌아가기") {
                    vm.capture.saveMessage = nil
                    UIApplication.shared.open(returnURL)
                    if closeAfterSave { closeNow() }
                }
            }
            Button("확인") {
                vm.capture.saveMessage = nil
                if closeAfterSave { closeNow() }
            }
        } message: {
            Text(vm.capture.saveMessage ?? "")
        }
        .sheet(isPresented: $showSRTShare, onDismiss: { if closeAfterSave { closeNow() } }) {
            ActivityView(items: shareItems)
                .presentationDetents([.medium, .large])
        }
    }

    private func closeNow() {
        vm.stopAll()
        dismiss()
    }

    /// X: a deliberate tap closes right away — unless a take is running, in
    /// which case ask, and keep the prompter up until the save alert is done.
    private func requestClose() {
        if vm.capture.isRecording { confirmClose = true } else { closeNow() }
    }

    private var saveAlertBinding: Binding<Bool> {
        Binding(get: { vm.capture.saveMessage != nil },
                set: { if !$0 { vm.capture.saveMessage = nil } })
    }

    private var recordingBadge: some View {
        HStack(spacing: 7) {
            Image(systemName: "record.circle.fill")
                .font(.system(size: 12))
                .foregroundStyle(.red)
                .symbolEffect(.pulse, options: .repeating)
            Text("녹화 중 \(timeString(vm.capture.recordingSeconds))")
                .font(.system(.footnote, design: .monospaced))
                .fontWeight(.semibold)
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(.red.opacity(0.28), in: Capsule())
        .overlay(Capsule().strokeBorder(.red.opacity(0.7), lineWidth: 1))
        .transition(.opacity.combined(with: .scale))
    }

    /// Camera permission was refused: the script and voice-follow still work,
    /// but recording can't. Sits under the top bar where it is reachable.
    private var cameraDeniedBanner: some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack(spacing: 7) {
                Image(systemName: "video.slash.fill")
                    .font(.system(size: 12))
                Text("설정 > 보이스 프롬프터에서 카메라를 켜 주세요")
                    .font(.footnote)
                Image(systemName: "chevron.right")
                    .font(.caption2)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 13)
            .padding(.vertical, 8)
            .background(.black.opacity(0.5), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.2), lineWidth: 1))
        }
    }

    private var rehearsalBadge: some View {
        HStack(spacing: 7) {
            Image(systemName: "waveform")
                .font(.system(size: 12))
                .foregroundStyle(.cyan)
                .symbolEffect(.variableColor.iterative, options: .repeating)
            Text("연습 중 \(timeString(vm.rehearsalSeconds))")
                .font(.system(.footnote, design: .monospaced))
                .fontWeight(.semibold)
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(.cyan.opacity(0.22), in: Capsule())
        .overlay(Capsule().strokeBorder(.cyan.opacity(0.6), lineWidth: 1))
        .transition(.opacity.combined(with: .scale))
    }

    private var isDemoBackdrop: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.environment["DEMO_CAMERA"] != nil
        #else
        return false
        #endif
    }

    /// True when the notch/front camera is on the leading (left) edge.
    /// interfaceOrientation .landscapeRight = home side right = notch left.
    static var cameraOnLeadingEdge: Bool {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ??
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        return scene?.interfaceOrientation == .landscapeRight
    }

    private var statusColor: Color {
        guard settings.voiceMode else { return .blue }
        if !settings.stageMode, vm.capture.isInterrupted { return .red }
        if vm.speechStatus == .interrupted { return .orange }
        return vm.lost ? .orange : .green
    }

    private var statusText: String {
        if !settings.voiceMode {
            return vm.isPaused ? AppSettings.tr("일시정지") : AppSettings.tr("자동 스크롤")
        }
        if !settings.stageMode, vm.capture.isInterrupted { return AppSettings.tr("카메라·마이크 중단됨") }
        if vm.speechStatus == .interrupted { return AppSettings.tr("마이크 중단됨 · 자동 스크롤") }
        if vm.lost { return AppSettings.tr("위치 찾는 중…") }
        switch vm.speechStatus {
        case .interrupted: return AppSettings.tr("마이크 중단됨 · 자동 스크롤")
        case .listening: return AppSettings.tr("듣는 중")
        case .idle: return AppSettings.tr("대기")
        case .denied: return AppSettings.tr("음성인식 권한 없음")
        case .unavailable: return AppSettings.tr("음성인식 사용 불가")
        case .dictationDisabled: return AppSettings.tr("받아쓰기가 꺼져 있어요")
        case .error(let m): return m
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button {
                requestClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.4), in: Circle())
            }

            Spacer()

            HStack(spacing: 6) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 7, height: 7)
                Text(statusText)
                    .font(.caption)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.black.opacity(0.4), in: Capsule())

            Spacer()

            // Stage mode: camera off, script only.
            Button {
                settings.stageMode.toggle()
            } label: {
                Image(systemName: settings.stageMode ? "video.slash.fill" : "video.fill")
                    .font(.system(size: 17))
                    .frame(width: 40, height: 40)
                    .background(settings.stageMode ? .cyan.opacity(0.45) : .black.opacity(0.4), in: Circle())
            }
            .accessibilityLabel(Text(settings.stageMode ? "카메라 켜기" : "카메라 끄기"))
            .disabled(vm.capture.isRecording)

            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 17))
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.4), in: Circle())
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
    }

    private var bottomBar: some View {
        VStack(spacing: 14) {
            if !settings.voiceMode {
                HStack(spacing: 10) {
                    Image(systemName: "tortoise.fill").font(.caption2)
                    Slider(value: $settings.autoSpeed, in: 10...150)
                    Image(systemName: "hare.fill").font(.caption2)
                }
                .foregroundStyle(.white.opacity(0.8))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.black.opacity(0.4), in: Capsule())
                .padding(.horizontal, 30)
            }

            HStack {
                // voice / auto mode toggle
                Button {
                    settings.voiceMode.toggle()
                } label: {
                    Image(systemName: settings.voiceMode ? "waveform.and.mic" : "timer")
                        .font(.system(size: 19))
                        .frame(width: 52, height: 52)
                        .background(.black.opacity(0.4), in: Circle())
                }

                Spacer()

                // record button (idle → 3·2·1 countdown → recording)
                if settings.stageMode {
                    Color.clear.frame(width: 74, height: 74)
                } else {
                Button {
                    vm.recordTapped()
                } label: {
                    ZStack {
                        Circle()
                            .strokeBorder(.white, lineWidth: 4)
                            .frame(width: 74, height: 74)
                        if let c = vm.countdown {
                            Text("\(c)")
                                .font(.system(size: 34, weight: .heavy, design: .rounded))
                                .foregroundStyle(.red)
                                .contentTransition(.numericText(countsDown: true))
                        } else {
                            RoundedRectangle(cornerRadius: vm.capture.isRecording ? 6 : 31)
                                .fill(.red)
                                .frame(width: vm.capture.isRecording ? 30 : 62,
                                       height: vm.capture.isRecording ? 30 : 62)
                                .animation(.easeInOut(duration: 0.18), value: vm.capture.isRecording)
                        }
                    }
                }
                .disabled(!vm.capture.cameraAvailable || vm.rehearsalActive)
                .opacity(vm.capture.cameraAvailable && !vm.rehearsalActive ? 1 : 0.35)
                }

                Spacer()

                // pause (auto mode) / rehearsal (voice mode)
                if !settings.voiceMode {
                    Button {
                        vm.togglePause()
                    } label: {
                        Image(systemName: vm.isPaused ? "play.fill" : "pause.fill")
                            .font(.system(size: 19))
                            .frame(width: 52, height: 52)
                            .background(.black.opacity(0.4), in: Circle())
                    }
                } else {
                    Button {
                        if vm.rehearsalActive { vm.finishRehearsal() } else { vm.startRehearsal() }
                    } label: {
                        Image(systemName: vm.rehearsalActive ? "stop.fill" : "text.book.closed.fill")
                            .font(.system(size: 19))
                            .frame(width: 52, height: 52)
                            .background(vm.rehearsalActive ? .cyan.opacity(0.5) : .black.opacity(0.4), in: Circle())
                    }
                    .accessibilityLabel(Text(vm.rehearsalActive ? "끝내기" : "연습"))
                    .disabled(vm.capture.isRecording || vm.countdown != nil)
                    .opacity(vm.capture.isRecording || vm.countdown != nil ? 0.35 : 1)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 34)
        }
        .padding(.bottom, 16)
    }

    /// Dictation switched off system-wide. Settings can't be deep-linked to
    /// the Keyboard pane, so spell the path out and offer the auto-scroll
    /// fallback so the reader isn't stuck.
    private var dictationOverlay: some View {
        VStack(spacing: 14) {
            Image(systemName: "waveform.slash")
                .font(.system(size: 44))
            Text("받아쓰기가 꺼져 있어요")
                .font(.headline)
            Text("설정 > 일반 > 키보드 > '받아쓰기 활성화'를 켜면\n목소리를 따라갈 수 있어요")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("자동 스크롤로 전환") {
                settings.voiceMode = false
            }
            .buttonStyle(.borderedProminent)
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
    }

    private var permissionOverlay: some View {
        VStack(spacing: 14) {
            Image(systemName: "mic.slash.circle")
                .font(.system(size: 44))
            Text("마이크·음성인식 권한이 필요해요")
                .font(.headline)
            Text("설정 > 보이스 프롬프터에서 권한을 켜 주세요")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("설정 열기") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(.borderedProminent)
            Button("자동 스크롤로 전환") {
                settings.voiceMode = false
            }
            .buttonStyle(.bordered)
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
    }

    private func timeString(_ s: Int) -> String {
        String(format: "%d:%02d", s / 60, s % 60)
    }
}
