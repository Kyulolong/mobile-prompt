import SwiftUI

/// Full-screen prompter: selfie camera behind, translucent scrim, the script
/// scrolling on top, and record/mode controls.
struct PrompterView: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var vm = PrompterViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showSettings = false
    @State private var showSRTShare = false
    let script: String

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
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
            }
            Color.black.opacity(settings.scrimOpacity)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            // In landscape the front camera sits on one side edge; a full-width
            // column makes the reader's gaze wander visibly off-lens. Narrow
            // the script and pin it to the camera's side (detected from the
            // interface orientation) so the eyes stay next to the lens.
            GeometryReader { geo in
                let landscape = geo.size.width > geo.size.height
                let boxWidth = landscape ? geo.size.width * 0.55
                                         : geo.size.width * settings.portraitWidthFrac
                let topPad = landscape ? 0 : geo.size.height * settings.portraitTopFrac
                let bottomPad = landscape ? 0 : geo.size.height * settings.portraitBottomFrac
                let bandHeight = geo.size.height - topPad - bottomPad

                HStack(spacing: 0) {
                    if !landscape || !Self.cameraOnLeadingEdge { Spacer(minLength: 0) }
                    ScriptTextView(viewModel: vm,
                                   tokens: vm.tokens,
                                   fontSize: settings.fontSize,
                                   readingLineFrac: settings.readingLineFrac)
                        .padding(.horizontal, 14)
                        .scaleEffect(x: settings.mirrored ? -1 : 1, y: 1)
                        .frame(width: boxWidth)
                        .padding(.top, topPad)
                        .padding(.bottom, bottomPad)
                    if !landscape || Self.cameraOnLeadingEdge { Spacer(minLength: 0) }
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
                Spacer()
                if vm.suggestedLang != nil {
                    languageHint
                }
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

            if vm.permissionState == .denied {
                permissionOverlay
            } else if vm.speechStatus == .dictationDisabled {
                dictationOverlay
            }
        }
        .statusBarHidden()
        .task {
            await vm.start(script: script, settings: settings)
        }
        .onDisappear { vm.stopAll() }
        .onChange(of: settings.voiceMode) { _, voice in vm.setMode(voice: voice) }
        .onChange(of: settings.autoSpeed) { _, v in vm.scroll.autoPxPerSec = v }
        .onChange(of: settings.readingLineFrac) { _, v in vm.scroll.readingLineFrac = v }
        .onChange(of: settings.highlightStyle) { _, v in vm.setHighlightStyle(v) }
        .sheet(isPresented: $showSettings) { SettingsSheet(compact: true) }
        .alert("안내", isPresented: saveAlertBinding) {
            if vm.srtFileURL != nil {
                Button("자막(SRT) 공유") {
                    vm.capture.saveMessage = nil
                    showSRTShare = true
                }
            }
            Button("확인") { vm.capture.saveMessage = nil }
        } message: {
            Text(vm.capture.saveMessage ?? "")
        }
        .sheet(isPresented: $showSRTShare) {
            if let url = vm.srtFileURL {
                ActivityView(items: [url])
                    .presentationDetents([.medium, .large])
            }
        }
    }

    private var saveAlertBinding: Binding<Bool> {
        Binding(get: { vm.capture.saveMessage != nil },
                set: { if !$0 { vm.capture.saveMessage = nil } })
    }

    /// Gentle capsule shown when the chosen recognition language doesn't match
    /// what the script looks like — one tap switches, ✕ keeps the choice.
    private var languageHint: some View {
        HStack(spacing: 10) {
            Image(systemName: "globe")
                .font(.footnote)
            Text("이 대본은 \(langName(vm.suggestedLang ?? "ko")) 대본 같아요")
                .font(.footnote)
            Button {
                withAnimation { vm.applySuggestedLang() }
            } label: {
                Text("전환")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.22), in: Capsule())
            }
            Button {
                withAnimation { vm.suggestedLang = nil }
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .padding(6)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(.black.opacity(0.55), in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.15)))
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func langName(_ code: String) -> String {
        code == "en" ? AppSettings.tr("영어") : AppSettings.tr("한국어")
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

    private var statusText: String {
        if !settings.voiceMode {
            return vm.isPaused ? AppSettings.tr("일시정지") : AppSettings.tr("자동 스크롤")
        }
        if vm.lost { return AppSettings.tr("위치 찾는 중…") }
        switch vm.speechStatus {
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
                vm.stopAll()
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.4), in: Circle())
            }

            Spacer()

            HStack(spacing: 6) {
                Circle()
                    .fill(settings.voiceMode ? (vm.lost ? Color.orange : Color.green) : Color.blue)
                    .frame(width: 7, height: 7)
                Text(statusText)
                    .font(.caption)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.black.opacity(0.4), in: Capsule())

            Spacer()

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
                .disabled(!vm.capture.cameraAvailable)
                .opacity(vm.capture.cameraAvailable ? 1 : 0.35)

                Spacer()

                // pause (auto mode) / recording timer
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
                    Color.clear.frame(width: 52, height: 52)
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
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
    }

    private func timeString(_ s: Int) -> String {
        String(format: "%d:%02d", s / 60, s % 60)
    }
}
