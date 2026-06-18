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
final class AVPlayerEngine: PlayerEngine {
    private let player = AVPlayer()
    private var timeObserverToken: Any?
    private var cancellables = Set<AnyCancellable>()

    private let currentTimeSubject = CurrentValueSubject<TimeInterval, Never>(0)
    private let durationSubject = CurrentValueSubject<TimeInterval, Never>(0)
    private let isPlayingSubject = CurrentValueSubject<Bool, Never>(false)
    private let isBufferingSubject = CurrentValueSubject<Bool, Never>(false)
    private let bufferedFractionSubject = CurrentValueSubject<Double, Never>(0)

    var currentTimePublisher: AnyPublisher<TimeInterval, Never> { currentTimeSubject.eraseToAnyPublisher() }
    var durationPublisher: AnyPublisher<TimeInterval, Never> { durationSubject.eraseToAnyPublisher() }
    var isPlayingPublisher: AnyPublisher<Bool, Never> { isPlayingSubject.eraseToAnyPublisher() }
    var isBufferingPublisher: AnyPublisher<Bool, Never> { isBufferingSubject.eraseToAnyPublisher() }
    var bufferedFractionPublisher: AnyPublisher<Double, Never> { bufferedFractionSubject.eraseToAnyPublisher() }

    var avPlayer: AVPlayer? { player }

    init() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self else { return }
            self.currentTimeSubject.send(time.seconds.isFinite ? time.seconds : 0)
            self.updateBufferedFraction()
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
        let item = AVPlayerItem(url: url)

        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .filter { $0 == .readyToPlay }
            .sink { [weak self, weak item] _ in
                guard let item, let dur = item.duration.seconds.isFinite ? item.duration.seconds : nil else { return }
                self?.durationSubject.send(dur)
            }
            .store(in: &cancellables)

        item.publisher(for: \.isPlaybackBufferEmpty)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] empty in if empty { self?.isBufferingSubject.send(true) } }
            .store(in: &cancellables)

        item.publisher(for: \.isPlaybackLikelyToKeepUp)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] likely in if likely { self?.isBufferingSubject.send(false) } }
            .store(in: &cancellables)

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
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    deinit { teardown() }

    private func updateBufferedFraction() {
        guard let item = player.currentItem,
              let range = item.loadedTimeRanges.first?.timeRangeValue,
              item.duration.seconds.isFinite, item.duration.seconds > 0 else { return }
        let bufferedEnd = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
        bufferedFractionSubject.send((bufferedEnd / item.duration.seconds).clampedUnit())
    }
}

private extension Double {
    func clampedUnit() -> Double { Swift.min(1, Swift.max(0, self)) }
}
