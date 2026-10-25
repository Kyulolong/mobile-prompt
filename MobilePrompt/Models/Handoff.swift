import Foundation

/// A script handed to the prompter by another app (Perplz) through the
/// `voiceprompter://script?...` URL scheme. See docs/perplz-integration.md.
///
///   s          percent-encoded script text (required, ≤ 16 KB UTF-8)
///   autostart  "1" → open the prompter immediately instead of the editor
///   return     URL to offer after a recording is saved; only perplz:// and
///              https://perplz.com/ are honored, anything else is dropped
struct HandoffRequest: Equatable {
    static let scheme = "voiceprompter"
    static let host = "script"
    static let maxBytes = 16 * 1024
    static let allowedReturnPrefixes = ["perplz://", "https://perplz.com/"]

    let text: String
    let autostart: Bool
    let returnURL: URL?

    static func parse(_ url: URL) -> HandoffRequest? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == host,
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        var text = ""
        var autostart = false
        var returnURL: URL?
        for item in items {
            switch item.name {
            case "s": text = item.value ?? ""
            case "autostart": autostart = item.value == "1" || item.value?.lowercased() == "true"
            case "return":
                if let v = item.value, allowedReturnPrefixes.contains(where: { v.lowercased().hasPrefix($0) }) {
                    returnURL = URL(string: v)
                }
            default: break
            }
        }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.utf8.count <= maxBytes else { return nil }
        return HandoffRequest(text: text, autostart: autostart, returnURL: returnURL)
    }
}
