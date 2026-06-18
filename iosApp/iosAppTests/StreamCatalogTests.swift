import XCTest
@testable import iosApp

final class StreamCatalogTests: XCTestCase {
    func test_a11y_namespace_isStable() {
        XCTAssertEqual(A11y.Player.playPause, "Player.button.playPause")
    }
}
