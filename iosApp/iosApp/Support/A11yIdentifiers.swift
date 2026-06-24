import Foundation

/// Centralized accessibility identifiers shared by the app (to tag views) and the
/// XCUITest target (to query them). Keeping them in one enum prevents string drift
/// between production views and UI tests.
enum A11y {
    enum StreamList {
        static let screen = "StreamList.screen"
        static let settingsButton = "StreamList.button.settings"
        /// Per-row identifier is `row(for:)` so tests can tap a specific stream by title.
        static func row(_ title: String) -> String { "StreamList.row.\(title)" }
    }

    enum Player {
        static let screen = "Player.screen"
        static let playPause = "Player.button.playPause"
        static let seekBack = "Player.button.seekBack"
        static let seekForward = "Player.button.seekForward"
        static let scrubber = "Player.slider.scrubber"
        static let positionLabel = "Player.label.position"
        static let durationLabel = "Player.label.duration"
        static let exit = "Player.button.exit"
    }

    enum Settings {
        static let screen = "Settings.screen"
        static let overlayToggle = "Settings.toggle.overlay"
        static let autoPlayToggle = "Settings.toggle.autoPlay"
        static let loopToggle = "Settings.toggle.loop"
        static let back = "Settings.button.back"
    }
}
