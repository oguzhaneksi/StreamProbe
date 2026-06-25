import AVFoundation
import Combine

/// Abstraction over the playback engine so `PlayerViewModel` logic (seek math, play/pause,
/// scrub gating, formatting) is unit-testable without a real `AVPlayer` or network.
/// State is exposed as Combine publishers; commands are imperative.
protocol PlayerEngine: AnyObject {
    var currentTimePublisher: AnyPublisher<TimeInterval, Never> { get }
    var durationPublisher: AnyPublisher<TimeInterval, Never> { get }
    var isPlayingPublisher: AnyPublisher<Bool, Never> { get }
    var isBufferingPublisher: AnyPublisher<Bool, Never> { get }
    var bufferedFractionPublisher: AnyPublisher<Double, Never> { get }
    /// The DVR/seekable window as `start...end` (absolute presentation seconds), or `nil` when no
    /// seekable range is available yet (VOD before ready, or live with no DVR window).
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> { get }

    /// The real AVPlayer when available (for `StreamProbe.attach(player:)`); `nil` for test doubles.
    var avPlayer: AVPlayer? { get }

    func load(url: URL)
    func play()
    func pause()
    func seek(to seconds: TimeInterval)
    func teardown()
}

/// Real `AVPlayer`-backed engine. Drives the playhead from a single periodic time observer and
/// discrete state from KVO publishers, all delivered on the main queue. Tears down observers in
/// `teardown()`/`deinit` to avoid the player→closure→self retain cycle.
///
/// Two separate cancellable sets are maintained:
/// - `cancellables` — long-lived player-level subscriptions (e.g. `timeControlStatus`).
/// - `itemCancellables` — per-`AVPlayerItem` subscriptions; cleared on each `load(url:)` so stale
///   sinks from a previous item don't keep firing into the shared subjects.
final class AVPlayerEngine: PlayerEngine {
    private let player = AVPlayer()
    private var timeObserverToken: Any?
    private var cancellables = Set<AnyCancellable>()
    /// Holds KVO subscriptions scoped to the current `AVPlayerItem`. Replaced on every `load(url:)`.
    private var itemCancellables = Set<AnyCancellable>()

    private let currentTimeSubject = CurrentValueSubject<TimeInterval, Never>(0)
    private let durationSubject = CurrentValueSubject<TimeInterval, Never>(0)
    private let isPlayingSubject = CurrentValueSubject<Bool, Never>(false)
    private let isBufferingSubject = CurrentValueSubject<Bool, Never>(false)
    private let bufferedFractionSubject = CurrentValueSubject<Double, Never>(0)
    private let seekableRangeSubject = CurrentValueSubject<ClosedRange<TimeInterval>?, Never>(nil)

    var currentTimePublisher: AnyPublisher<TimeInterval, Never> { currentTimeSubject.eraseToAnyPublisher() }
    var durationPublisher: AnyPublisher<TimeInterval, Never> { durationSubject.eraseToAnyPublisher() }
    var isPlayingPublisher: AnyPublisher<Bool, Never> { isPlayingSubject.eraseToAnyPublisher() }
    var isBufferingPublisher: AnyPublisher<Bool, Never> { isBufferingSubject.eraseToAnyPublisher() }
    var bufferedFractionPublisher: AnyPublisher<Double, Never> { bufferedFractionSubject.eraseToAnyPublisher() }
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> {
        seekableRangeSubject.eraseToAnyPublisher()
    }

    var avPlayer: AVPlayer? { player }

    init() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self else { return }
            self.currentTimeSubject.send(time.seconds.isFinite ? time.seconds : 0)
            self.updateBufferedFraction()
            self.updateSeekableRange()
        }

        player.publisher(for: \.timeControlStatus)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                self?.isPlayingSubject.send(status == .playing)
                self?.isBufferingSubject.send(status == .waitingToPlayAtSpecifiedRate)
            }
            .store(in: &cancellables)
    }

    func load(url: URL) {
        // Cancel stale per-item subscriptions from any previous load.
        itemCancellables.removeAll()
        // Reset duration immediately so the new item doesn't briefly publish the previous value.
        durationSubject.send(0)
        seekableRangeSubject.send(nil)

        let item = AVPlayerItem(url: url)

        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .filter { $0 == .readyToPlay }
            .sink { [weak self, weak item] _ in
                guard let item, let dur = item.duration.seconds.isFinite ? item.duration.seconds : nil else { return }
                self?.durationSubject.send(dur)
            }
            .store(in: &itemCancellables)

        item.publisher(for: \.isPlaybackBufferEmpty)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] empty in if empty { self?.isBufferingSubject.send(true) } }
            .store(in: &itemCancellables)

        item.publisher(for: \.isPlaybackLikelyToKeepUp)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] likely in if likely { self?.isBufferingSubject.send(false) } }
            .store(in: &itemCancellables)

        player.replaceCurrentItem(with: item)
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func seek(to seconds: TimeInterval) {
        let target = CMTime(seconds: seconds, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func teardown() {
        if let token = timeObserverToken { player.removeTimeObserver(token); timeObserverToken = nil }
        cancellables.removeAll()
        itemCancellables.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    deinit { teardown() }

    private func updateBufferedFraction() {
        guard let item = player.currentItem,
              let range = item.loadedTimeRanges.first?.timeRangeValue,
              item.duration.seconds.isFinite, item.duration.seconds > 0 else { return }
        let bufferedEnd = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
        // Guard NaN: indefinite ranges produce NaN here; `clampedUnit()` does not catch it.
        guard bufferedEnd.isFinite else { return }
        bufferedFractionSubject.send((bufferedEnd / item.duration.seconds).clampedUnit())
    }

    private func updateSeekableRange() {
        guard let item = player.currentItem,
              let range = item.seekableTimeRanges.last?.timeRangeValue else {
            seekableRangeSubject.send(nil)
            return
        }
        let start = range.start.seconds
        let end = CMTimeRangeGetEnd(range).seconds
        guard start.isFinite, end.isFinite, end > start else {
            seekableRangeSubject.send(nil)
            return
        }
        seekableRangeSubject.send(start...end)
    }
}

private extension Double {
    func clampedUnit() -> Double { Swift.min(1, Swift.max(0, self)) }
}
