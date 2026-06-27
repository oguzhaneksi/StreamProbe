import XCTest
import StreamProbeCore
@testable import StreamProbe

/// Locks the pure Swift formatters that hand-mirror commonMain's `SegmentFormatters`.
/// The cases below are kept identical to the Kotlin `OverlayFormattingTest` so the two
/// platforms can't drift — including the edge inputs (`seg.ts/`, `?seg.ts`, whitespace
/// extension) where the previous `split`-based Swift port diverged from Kotlin.
final class OverlayFormattersSwiftTests: XCTestCase {

    // ── segmentExtension ──────────────────────────────────────────
    func testSegmentExtensionReadsAPlainUri() {
        XCTAssertEqual("ts", OverlayFormattersSwift.segmentExtension("https://host/path/seg3.ts"))
    }

    func testSegmentExtensionStripsQueryString() {
        XCTAssertEqual("ts", OverlayFormattersSwift.segmentExtension("https://host/path/seg3.ts?token=abc"))
    }

    func testSegmentExtensionStripsFragment() {
        XCTAssertEqual("m4s", OverlayFormattersSwift.segmentExtension("https://host/path/seg3.m4s#frag"))
    }

    func testSegmentExtensionReturnsNilForExtensionlessPath() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("https://host/path/segment"))
    }

    func testSegmentExtensionReturnsNilForUnknownUri() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("(unknown)"))
    }

    func testSegmentExtensionReturnsNilForOverlongExtension() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("https://host/path/file.megabyte"))
    }

    // Parity edge cases (previously diverged from Kotlin).
    func testSegmentExtensionReturnsNilForTrailingSlash() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("https://host/path/seg.ts/"))
    }

    func testSegmentExtensionReturnsNilForLeadingQuery() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("?seg.ts"))
    }

    func testSegmentExtensionReturnsNilForWhitespaceExtension() {
        XCTAssertNil(OverlayFormattersSwift.segmentExtension("https://host/path/file. "))
    }

    // ── segmentTrackBadge ─────────────────────────────────────────
    func testSegmentTrackBadgeMapsEachTrackType() {
        XCTAssertEqual("V", OverlayFormattersSwift.segmentTrackBadge(.video))
        XCTAssertEqual("A", OverlayFormattersSwift.segmentTrackBadge(.audio))
        XCTAssertEqual("T", OverlayFormattersSwift.segmentTrackBadge(.text))
        XCTAssertNil(OverlayFormattersSwift.segmentTrackBadge(.unknown))
    }
}
