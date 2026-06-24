import SwiftUI

/// SwiftUI entry point. Hosts the stream-selection UI in a `WindowGroup`; the debug overlay lives
/// in a separate `UIWindow` created by `SceneDelegate`. Both share `AppDependencies.shared.probe`.
@main
struct DemoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let deps = AppDependencies.shared

    var body: some Scene {
        WindowGroup {
            StreamSelectionScreen(probe: deps.probe, settings: deps.settings)
                .preferredColorScheme(.dark)
        }
    }
}
