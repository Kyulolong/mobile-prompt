import SwiftUI

@main
struct MobilePromptApp: App {
    @StateObject private var settings = AppSettings()
    @StateObject private var store = ScriptStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(settings)
                .environmentObject(store)
                .environment(\.locale, settings.uiLocale)
                .preferredColorScheme(.dark)
        }
    }
}
