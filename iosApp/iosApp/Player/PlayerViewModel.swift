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
    }

    /// The real AVPlayer for `StreamProbe.attach(player:)`, or nil under test.
    var avPlayer: AVPlayer? { engine.avPlayer }

    func attach(streamURL: URL, autoPlay: Bool) {
        engine.load(url: streamURL)
        if autoPlay { engine.play() }
    }

    func togglePlayPause() { isPlaying ? engine.pause() : engine.play() }
    func play() { engine.play() }
    func pause() { engine.pause() }

    func seekForward() { engine.seek(to: min(duration, currentTime + Self.seekStep)) }
    func seekBack() { engine.seek(to: max(0, currentTime - Self.seekStep)) }

    func beginScrub() { scrubState = .scrubbing }

    func commitScrub(to seconds: TimeInterval) {
        let clamped = min(max(0, seconds), duration > 0 ? duration : seconds)
        currentTime = clamped
        engine.seek(to: clamped)
        scrubState = .idle
    }

    func teardown() { engine.teardown() }

    var positionText: String { Self.mmss(currentTime) }
    var remainingText: String { "-" + Self.mmss(max(0, duration - currentTime)) }

    private static func mmss(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.isFinite ? seconds : 0)
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}
