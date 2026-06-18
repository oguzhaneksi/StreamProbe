import XCTest
import Combine
@testable import iosApp

final class PlayerViewModelTests: XCTestCase {
    private var engine: MockPlayerEngine!
    private var vm: PlayerViewModel!

    override func setUp() {
        super.setUp()
        engine = MockPlayerEngine()
        vm = PlayerViewModel(engine: engine)
    }

    func test_attach_loadsUrl_andStartsPlay_whenAutoPlay() {
        let url = URL(string: "https://example.com/master.m3u8")!
        vm.attach(streamURL: url, autoPlay: true)
        XCTAssertEqual(engine.loadedURL, url)
        XCTAssertEqual(engine.playCount, 1)
    }

    func test_attach_doesNotPlay_whenAutoPlayOff() {
        vm.attach(streamURL: URL(string: "https://example.com/a.m3u8")!, autoPlay: false)
        XCTAssertEqual(engine.playCount, 0)
    }

    func test_togglePlayPause_pausesWhenPlaying_playsWhenPaused() {
        engine.isPlayingSubject.send(true)
        vm.togglePlayPause()
        XCTAssertEqual(engine.pauseCount, 1)

        engine.isPlayingSubject.send(false)
        vm.togglePlayPause()
        XCTAssertEqual(engine.playCount, 1)
    }

    func test_seekForward_addsTenSeconds_clampedToDuration() {
        engine.durationSubject.send(100)
        engine.currentTimeSubject.send(95)
        vm.seekForward()
        XCTAssertEqual(engine.seekTargets.last, 100) // 95 + 10 clamped to 100
    }

    func test_seekBack_subtractsTenSeconds_clampedToZero() {
        engine.currentTimeSubject.send(4)
        vm.seekBack()
        XCTAssertEqual(engine.seekTargets.last, 0) // 4 - 10 clamped to 0
    }

    func test_scrub_gatesTimeUpdates_thenCommitsSeek() {
        engine.durationSubject.send(200)
        vm.beginScrub()
        engine.currentTimeSubject.send(50) // should NOT move displayed time during scrub
        XCTAssertEqual(vm.currentTime, 0)
        vm.commitScrub(to: 120)
        XCTAssertEqual(engine.seekTargets.last, 120)
    }

    func test_positionText_and_remainingText_format_mmss() {
        engine.durationSubject.send(125) // 02:05
        engine.currentTimeSubject.send(65) // 01:05
        XCTAssertEqual(vm.positionText, "01:05")
        XCTAssertEqual(vm.remainingText, "-01:00")
    }

    func test_bufferedFraction_passesThrough() {
        engine.bufferedFractionSubject.send(0.4)
        XCTAssertEqual(vm.bufferedFraction, 0.4, accuracy: 0.001)
    }

    func test_teardown_forwardsToEngine() {
        vm.teardown()
        XCTAssertEqual(engine.teardownCount, 1)
    }
}
