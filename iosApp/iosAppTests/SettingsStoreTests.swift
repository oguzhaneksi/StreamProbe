import XCTest
import Combine
@testable import iosApp

final class SettingsStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let suite = "SettingsStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    func test_defaults_areOverlayOn_autoPlayOn_loopOff() {
        let store = SettingsStore(defaults: makeDefaults())
        XCTAssertTrue(store.overlayVisible)
        XCTAssertTrue(store.autoPlay)
        XCTAssertFalse(store.loop)
    }

    func test_setting_isPersistedAcrossInstances() {
        let defaults = makeDefaults()
        let first = SettingsStore(defaults: defaults)
        first.overlayVisible = false
        first.loop = true

        let second = SettingsStore(defaults: defaults)
        XCTAssertFalse(second.overlayVisible)
        XCTAssertTrue(second.loop)
    }

    func test_overlayVisible_change_publishes() {
        let store = SettingsStore(defaults: makeDefaults())
        var received: [Bool] = []
        let cancellable = store.$overlayVisible.sink { received.append($0) }
        store.overlayVisible = false
        cancellable.cancel()
        XCTAssertEqual(received, [true, false])
    }
}
