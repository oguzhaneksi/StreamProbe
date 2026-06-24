import AVFoundation
import Combine
@testable import iosApp

/// Test double for `PlayerEngine`. Records commands and lets tests drive state via subjects.
final class MockPlayerEngine: PlayerEngine {
    let currentTimeSubject = CurrentValueSubject<TimeInterval, Never>(0)
    let durationSubject = CurrentValueSubject<TimeInterval, Never>(0)
    let isPlayingSubject = CurrentValueSubject<Bool, Never>(false)
    let isBufferingSubject = CurrentValueSubject<Bool, Never>(false)
    let bufferedFractionSubject = CurrentValueSubject<Double, Never>(0)
    let seekableRangeSubject = CurrentValueSubject<ClosedRange<TimeInterval>?, Never>(nil)

    var currentTimePublisher: AnyPublisher<TimeInterval, Never> { currentTimeSubject.eraseToAnyPublisher() }
    var durationPublisher: AnyPublisher<TimeInterval, Never> { durationSubject.eraseToAnyPublisher() }
    var isPlayingPublisher: AnyPublisher<Bool, Never> { isPlayingSubject.eraseToAnyPublisher() }
    var isBufferingPublisher: AnyPublisher<Bool, Never> { isBufferingSubject.eraseToAnyPublisher() }
    var bufferedFractionPublisher: AnyPublisher<Double, Never> { bufferedFractionSubject.eraseToAnyPublisher() }
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> {
        seekableRangeSubject.eraseToAnyPublisher()
    }

    var avPlayer: AVPlayer? { nil }

    private(set) var loadedURL: URL?
    private(set) var playCount = 0
    private(set) var pauseCount = 0
    private(set) var seekTargets: [TimeInterval] = []
    private(set) var teardownCount = 0

    func load(url: URL) { loadedURL = url }
    func play() { playCount += 1; isPlayingSubject.send(true) }
    func pause() { pauseCount += 1; isPlayingSubject.send(false) }
    func seek(to seconds: TimeInterval) { seekTargets.append(seconds); currentTimeSubject.send(seconds) }
    func teardown() { teardownCount += 1 }
}
