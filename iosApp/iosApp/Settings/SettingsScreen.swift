import StreamProbe
import SwiftUI

/// Demo settings (overlay + playback prefs). The overlay toggle drives the overlay window's
/// visibility through `AppDependencies` (bound via `SettingsStore.overlayVisible`); auto-play and
/// loop affect the next/created player. No "inject errors" toggle — AVPlayer has no DataSource hook.
struct SettingsScreen: View {
    @ObservedObject var settings: SettingsStore
    let probe: StreamProbe_
    let onClose: () -> Void

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Overlay")) {
                    Toggle("Show debug overlay", isOn: $settings.overlayVisible)
                        .accessibilityIdentifier(A11y.Settings.overlayToggle)
                }
                Section(header: Text("Playback")) {
                    Toggle("Auto-play on select", isOn: $settings.autoPlay)
                        .accessibilityIdentifier(A11y.Settings.autoPlayToggle)
                    Toggle("Loop playback", isOn: $settings.loop)
                        .accessibilityIdentifier(A11y.Settings.loopToggle)
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done", action: onClose).accessibilityIdentifier(A11y.Settings.back)
                }
            }
            .accessibilityIdentifier(A11y.Settings.screen)
        }
        .navigationViewStyle(.stack)
    }
}
