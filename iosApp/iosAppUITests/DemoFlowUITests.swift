import XCTest

/// XCUITest flow suite. Expanded in Task 11. Placeholder keeps the target compiling.
final class DemoFlowUITests: XCTestCase {
    func test_appLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
    }
}
