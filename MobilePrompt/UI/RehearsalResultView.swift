import SwiftUI

/// Summary after a rehearsal (or a take): time, pace, pauses, and the
/// sentences that caused trouble.
struct RehearsalResultView: View {
    let report: RehearsalReport
    var onAgain: () -> Void = {}
    var onRecord: () -> Void = {}
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                        tile("총 시간", Self.timeString(report.seconds))
                        tile("읽기 속도", paceString)
                        tile("멈춤", pauseString)
                        tile("막힌 곳", "\(report.stuck.count)")
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                Section("막힌 문장") {
                    if report.stuck.isEmpty {
                        Label("막힌 곳 없이 잘 읽었어요", systemImage: "checkmark.circle")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(report.stuck) { s in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(s.text)
                                    .font(.subheadline)
                                HStack(spacing: 6) {
                                    ForEach(Array(s.reasons.enumerated()), id: \.offset) { _, reason in
                                        Text(Self.reasonText(reason))
                                            .font(.caption.weight(.semibold))
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.orange.opacity(0.18), in: Capsule())
                                            .foregroundStyle(.orange)
                                    }
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
                Section {
                    if report.kind == .rehearsal {
                        Button {
                            dismiss()
                            onAgain()
                        } label: {
                            Label("다시 연습", systemImage: "arrow.counterclockwise")
                        }
                        Button {
                            dismiss()
                            onRecord()
                        } label: {
                            Label("녹화하러 가기", systemImage: "record.circle")
                        }
                    } else {
                        Button("확인") { dismiss() }
                    }
                }
            }
            .navigationTitle(report.kind == .rehearsal ? "연습 결과" : "테이크 결과")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func tile(_ title: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.weight(.semibold))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var paceString: String {
        let n = String(Int(report.pace.rounded()))
        return report.paceUnit == .wordsPerMinute
            ? String(format: AppSettings.tr("%@ wpm"), n)
            : String(format: AppSettings.tr("분당 %@음절"), n)
    }

    private var pauseString: String {
        String(format: AppSettings.tr("%@회 · %@초"), String(report.pauseCount), Self.secondsString(report.pauseSeconds))
    }

    static func reasonText(_ reason: StuckReason) -> String {
        switch reason {
        case .pause(let s): return String(format: AppSettings.tr("멈춤 %@초"), secondsString(s))
        case .reread: return AppSettings.tr("다시 읽음")
        case .lost: return AppSettings.tr("놓침")
        case .slow: return AppSettings.tr("느림")
        }
    }

    static func timeString(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    static func secondsString(_ seconds: Double) -> String {
        seconds < 10 ? String(format: "%.1f", seconds) : String(Int(seconds.rounded()))
    }
}
