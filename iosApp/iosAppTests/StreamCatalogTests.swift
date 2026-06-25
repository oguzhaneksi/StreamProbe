import XCTest
@testable import iosApp

final class StreamCatalogTests: XCTestCase {
    func test_catalog_isNonEmpty() {
        XCTAssertFalse(demoStreams.isEmpty)
    }

    func test_allStreams_areHlsM3U8() {
        for stream in demoStreams {
            XCTAssertTrue(
                stream.url.absoluteString.contains(".m3u8"),
                "\(stream.title) is not an HLS (.m3u8) stream"
            )
        }
    }

    func test_allStreams_haveHttpsUrls() {
        for stream in demoStreams {
            XCTAssertEqual(stream.url.scheme, "https", "\(stream.title) is not https")
        }
    }

    func test_stream_titles_areUnique() {
        let titles = demoStreams.map(\.title)
        XCTAssertEqual(titles.count, Set(titles).count, "stream titles must be unique")
    }

    func test_atLeastOneLiveStream_exists() {
        XCTAssertTrue(demoStreams.contains { $0.isLive }, "catalog must contain a live stream")
    }

    func test_vodStreams_areNotLive() {
        let vod = demoStreams.filter { !$0.isLive }
        XCTAssertEqual(vod.count, 5, "the 5 original VOD entries must remain non-live")
    }

    func test_allStreams_haveUniqueIds() {
        let ids = demoStreams.map(\.id)
        XCTAssertEqual(ids.count, Set(ids).count, "stream ids must be unique")
    }
}
