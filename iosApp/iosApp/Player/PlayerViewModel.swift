import AVFoundation
import Combine
import Foundation

/// Drives the SwiftUI player from a `PlayerEngine`. Holds the engine (state survives view
/// re-creation), exposes formatted/derived state, owns seek±10 math and the scrub-gating
/// state machine that prevents the slider↔time-observer feedback loop.
final class PlayerViewModel: ObservableObject {
    enum ScrubState { case idle, scrubbing }

    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var bufferedFraction: Double = 0
    @Published var scrubState: ScrubState = .idle
    @Published private(set) var isLive = false
    @Published private(set) var seekableRange: ClosedRange<TimeInterval>?

    private let engine: PlayerEngine
    private var cancellables = Set<AnyCancellable>()
    private static let seekStep: TimeInterval = 10

    init(engine: PlayerEngine) {
        self.engine = engine

        engine.currentTimePublisher
            .sink { [weak self] t in
                guard let self, self.scrubState == .idle else { return }
                self.currentTime = t
            }
            .store(in: &cancellables)

        engine.durationPublisher.sink { [weak self] in self?.duration = $0 }.store(in: &cancellables)
        engine.isPlayingPublisher.sink { [weak self] in self?.isPlaying = $0 }.store(in: &cancellables)
        engine.isBufferingPublisher.sink { [weak self] in self?.isBuffering = $0 }.store(in: &cancellables)
        engine.bufferedFractionPublisher.sink { [weak self] in self?.bufferedFraction = $0 }.store(in: &cancellables)
        engine.seekableRangePublisher.sink { [weak self] in self?.seekableRange = $0 }
            .store(in: &cancellables)
    }

    /// The real AVPlayer for `StreamProbe.attach(player:)`, or nil under test.
    var avPlayer: AVPlayer? { engine.avPlayer }

    func attach(streamURL: URL, autoPlay: Bool, isLive: Bool = false) {
        self.isLive = isLive
        engine.load(url: streamURL)
        if autoPlay { engine.play() }
    }

    func togglePlayPause() { isPlaying ? engine.pause() : engine.play() }
    func play() { engine.play() }
    func pause() { engine.pause() }

    func seekForward() {
        if isLive {
            let upper = seekableRange?.upperBound ?? (currentTime + Self.seekStep)
            engine.seek(to: min(upper, currentTime + Self.seekStep))
            return
        }
        engine.seek(to: min(duration, currentTime + Self.seekStep))
    }

    func seekBack() {
        let lower = isLive ? (seekableRange?.lowerBound ?? 0) : 0
        engine.seek(to: max(lower, currentTime - Self.seekStep))
    }

    func beginScrub() { scrubState = .scrubbing }

    func commitScrub(to seconds: TimeInterval) {
        if isLive {
            let lower = seekableRange?.lowerBound ?? 0
            let absolute = lower + seconds                 // scrubber value is DVR-relative
            let upper = seekableRange?.upperBound ?? absolute
            let clamped = min(max(lower, absolute), upper)
            currentTime = clamped
            engine.seek(to: clamped)
            scrubState = .idle
            return
        }
        let clamped = min(max(0, seconds), duration > 0 ? duration : seconds)
        currentTime = clamped
        engine.seek(to: clamped)
        scrubState = .idle
    }

    func teardown() { engine.teardown() }

    /// Upper bound for the scrubber's value space. Live uses DVR-window length (0-based);
    /// VOD uses absolute duration.
    var scrubUpperBound: Double {
        if isLive { return seekableRange.map { $0.upperBound - $0.lowerBound } ?? 1 }
        return Swift.max(duration, 1)
    }

    /// Current scrubber value in its own coordinate space: DVR-relative for live, absolute for VOD.
    var scrubValue: Double {
        if isLive { return currentTime - (seekableRange?.lowerBound ?? 0) }
        return currentTime
    }

    var positionText: String {
        if isLive {
            guard let range = seekableRange else { return Self.mmss(0) }
            return Self.mmss(currentTime - range.lowerBound)
        }
        return Self.mmss(currentTime)
    }

    var remainingText: String {
        if isLive {
            guard let range = seekableRange else { return "-" + Self.mmss(0) }
            return "-" + Self.mmss(max(0, range.upperBound - currentTime))
        }
        return "-" + Self.mmss(max(0, duration - currentTime))
    }

    private static func mmss(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.isFinite ? seconds : 0)
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}
