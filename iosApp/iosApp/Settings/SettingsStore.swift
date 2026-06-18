import Combine
import Foundation

/// Persisted demo preferences. Backed by an injectable `UserDefaults` so tests can use an
/// isolated suite. Defaults: overlay visible + auto-play ON, loop OFF.
final class SettingsStore: ObservableObject {
    private enum Key {
        static let overlayVisible = "settings.overlayVisible"
        static let autoPlay = "settings.autoPlay"
        static let loop = "settings.loop"
    }

    private let defaults: UserDefaults

    @Published var overlayVisible: Bool { didSet { defaults.set(overlayVisible, forKey: Key.overlayVisible) } }
    @Published var autoPlay: Bool { didSet { defaults.set(autoPlay, forKey: Key.autoPlay) } }
    @Published var loop: Bool { didSet { defaults.set(loop, forKey: Key.loop) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.overlayVisible = defaults.object(forKey: Key.overlayVisible) as? Bool ?? true
        self.autoPlay = defaults.object(forKey: Key.autoPlay) as? Bool ?? true
        self.loop = defaults.object(forKey: Key.loop) as? Bool ?? false
    }
}
