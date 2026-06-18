import XCTest

/// End-to-end navigation flow. Asserts UI presence and navigation only — NOT playback progression
/// (the CI simulator can't load HLS over TLS). Identifiers mirror `A11y` (literals, since the
/// UI-test target doesn't link app code).
///
/// Notes on deviations from the brief's verbatim code:
/// - `app.collectionViews["StreamList.screen"]` instead of `app.otherElements["StreamList.screen"]`:
///   SwiftUI `List` renders as a `UICollectionView`; XCUITest exposes it as `.collectionView`,
///   not `.other`. Using the correct element type makes the assertion reliable.
/// - `test_settings_opensAndTogglesOverlay` waits for the list to be ready before tapping the
///   settings button (without a guard wait the toolbar button tap can miss on slow launch).
final class DemoFlowUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    func test_launch_showsStreamList() {
        XCTAssertTrue(app.collectionViews["StreamList.screen"].waitForExistence(timeout: 10))
    }

    func test_selectStream_opensPlayer_thenExitReturnsToList() {
        let firstRow = app.buttons["StreamList.row.Apple BipBop — Advanced (TS)"]
        XCTAssertTrue(firstRow.waitForExistence(timeout: 10))
        firstRow.tap()

        let exit = app.buttons["Player.button.exit"]
        XCTAssertTrue(exit.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Player.button.playPause"].exists)
        XCTAssertTrue(app.sliders["Player.slider.scrubber"].exists)

        exit.tap()
        XCTAssertTrue(app.collectionViews["StreamList.screen"].waitForExistence(timeout: 10))
    }

    func test_settings_opensAndTogglesOverlay() {
        // Wait for the list to be ready before tapping the toolbar button.
        XCTAssertTrue(app.collectionViews["StreamList.screen"].waitForExistence(timeout: 10))
        app.buttons["StreamList.button.settings"].tap()
        let overlayToggle = app.switches["Settings.toggle.overlay"]
        XCTAssertTrue(overlayToggle.waitForExistence(timeout: 10))
        overlayToggle.tap() // flip it
        app.buttons["Settings.button.back"].tap()
        XCTAssertTrue(app.collectionViews["StreamList.screen"].waitForExistence(timeout: 10))
    }
}
