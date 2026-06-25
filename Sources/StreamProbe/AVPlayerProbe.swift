import AVFoundation
import QuartzCore
import StreamProbeCore

/// iOS analogue of the Kotlin `AVPlayerProbe`: observes an `AVPlayer` and writes the SDK models into
/// the Core through a write-only `DiagnosticsSink`. Headless — no UI.
///
/// Because the sink is write-only, the probe keeps its own mirror of the state the Kotlin version read
/// back from the store (`currentSnapshot`, `currentActiveTrack`, `hasSelectedAudio`). The probe is the
/// sole writer on iOS, so its local copy is authoritative.
///
/// Not thread-safe: all callbacks arrive on the main queue (notification observers use `.main`; the
/// variants-load completion hops to main). The iOS 18 AVMetrics consumer task runs off the main thread
/// between awaits, but all `sink` access remains pinned to `MainActor`. Holds the player weakly, but on
/// iOS 18+ the AVMetrics consumer `Task` retains the current `AVPlayerItem` until `detach()` cancels it,
/// so the host must `detach()` before releasing the player.
final class AVPlayerProbe {
    private let sink: DiagnosticsSink
    private weak var player: AVPlayer?
    private var active = false
    private var observers: [NSObjectProtocol] = []

    private var processedEventEntries = 0
    private var finalizedSegmentEntries = 0
    private var processedErrorEntries = 0
    private var lastIndicatedBitrate = -1.0

    private var currentSnapshot: TracksSnapshot?
    private var currentActiveTrack: ActiveTrackInfo?
    private var hasSelectedAudio = false

    private var epochOffsetMs: Int64 = 0
    private var metricsTask: Task<Void, Never>?

    private let variantsKey = "variants"
    private let droppedFrameThreshold = 3

    init(sink: DiagnosticsSink) {
        self.sink = sink
    }

    func attach(player: AVPlayer) {
        // Re-attaching the same player instance would duplicate observers and the metrics consumer; no-op.
        // (Item swaps on the same player are not re-observed — item-change tracking is not yet supported.)
        if active, self.player === player { return }
        detach()
        self.player = player
        active = true
        epochOffsetMs = Int64(Date().timeIntervalSince1970 * 1000) - Int64(CACurrentMediaTime() * 1000)
        registerLogObservers()
        startMetricsConsumer()
        guard let asset = player.currentItem?.asset as? AVURLAsset else { return }
        asset.loadValuesAsynchronously(forKeys: [variantsKey]) { [weak self] in
            DispatchQueue.main.async {
                guard let self, self.active, self.player?.currentItem?.asset === asset else { return }
                var error: NSError?
                if asset.statusOfValue(forKey: self.variantsKey, error: &error) == .loaded {
                    self.publishTracks(asset)
                }
            }
        }
    }

    func detach() {
        // Flush pending entries before teardown (manual-stop case; natural end is handled by the
        // DidPlayToEndTime observer).
        onNewAccessLogEntry()
        flushLastSegmentEntry()
        active = false
        let center = NotificationCenter.default
        observers.forEach { center.removeObserver($0) }
        observers.removeAll()
        metricsTask?.cancel()
        metricsTask = nil
        player = nil
        processedEventEntries = 0
        finalizedSegmentEntries = 0
        processedErrorEntries = 0
        lastIndicatedBitrate = -1.0
        currentSnapshot = nil
        currentActiveTrack = nil
        hasSelectedAudio = false
        epochOffsetMs = 0
    }

    private func registerLogObservers() {
        let center = NotificationCenter.default
        let item = player?.currentItem
        observers.append(center.addObserver(forName: .AVPlayerItemNewAccessLogEntry, object: item, queue: .main) { [weak self] _ in
            guard let self, self.active else { return }
            self.onNewAccessLogEntry()
        })
        observers.append(center.addObserver(forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main) { [weak self] _ in
            guard let self, self.active else { return }
            self.onNewErrorLogEntry()
        })
        observers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            guard let self, self.active else { return }
            self.onNewAccessLogEntry()
            self.flushLastSegmentEntry()
        })
    }

    private func startMetricsConsumer() {
        guard #available(iOS 18.0, *), let item = player?.currentItem else { return }
        metricsTask = Task { [weak self] in
            do {
                for try await event in item.metrics(forType: AVMetricHLSMediaSegmentRequestEvent.self) {
                    await MainActor.run { [weak self] in
                        guard let self, self.active else { return }
                        self.sink.addSegmentMetric(metric: AVMetricsSegmentAdapter.segmentMetric(from: event, nowMs: self.nowMs()))
                    }
                }
            } catch is CancellationError {
                // Expected teardown path: detach() cancelled the task.
            } catch {
                // AVFoundation metrics stream error; swallowed so the debug probe never crashes the host app.
                // TODO(future release): surface/handle this instead of going silent — on iOS 18+ there is no
                // access-log fallback for segment metrics, so a dead stream means no per-segment data at all.
            }
        }
    }

    private func publishTracks(_ asset: AVURLAsset) {
        let variants = asset.variants.map { mapVariant($0) }
        guard !variants.isEmpty else { return }
        let mediaSelection = player?.currentItem?.currentMediaSelection
        let audioGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .audible)
        let selectedAudio = audioGroup.flatMap { mediaSelection?.selectedMediaOption(in: $0) }
        let audioTracks = (audioGroup?.options ?? []).map { mapAudioOption($0, isSelected: $0.isEqual(selectedAudio)) }
        let subtitleGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .legible)
        let selectedSubtitle = subtitleGroup.flatMap { mediaSelection?.selectedMediaOption(in: $0) }
        let subtitleTracks = (subtitleGroup?.options ?? []).map { mapLegibleOption($0, isSelected: $0.isEqual(selectedSubtitle)) }
        let snapshot = TracksSnapshot(variants: variants, audioTracks: audioTracks, subtitleTracks: subtitleTracks)
        sink.updateTrackList(info: snapshot)
        currentSnapshot = snapshot
        let activeAudio = audioTracks.first { $0.isSelected }
        sink.updateActiveAudioTrack(info: activeAudio)
        hasSelectedAudio = activeAudio != nil
        sink.updateActiveSubtitleTrack(info: subtitleTracks.first { $0.isSelected })
    }

    private func onNewAccessLogEntry() {
        guard let events = player?.currentItem?.accessLog()?.events else { return }
        // Bitrate switches and dropped frames: process every entry immediately, including the live tail.
        // These fields stabilise as soon as the entry is created, so waiting adds visible overlay lag.
        while processedEventEntries < events.count {
            let event = events[processedEventEntries]
            let tsMs = event.playbackStartDate.map { Int64($0.timeIntervalSince1970 * 1000) } ?? nowMs()
            emitBitrateSwitch(nowMs: tsMs, indicatedBitrate: event.indicatedBitrate)
            if event.numberOfDroppedVideoFrames >= droppedFrameThreshold {
                sink.addPlaybackError(event: AVAccessLogMappersKt.droppedFramesError(
                    nowMs: tsMs,
                    droppedFrames: Int64(event.numberOfDroppedVideoFrames)
                ))
            }
            processedEventEntries += 1
        }
        // Segment metrics (pre-iOS 18 only): defer the last entry because AVFoundation is still
        // accumulating bytes, transfer duration, and observed bitrate into it. Each new entry
        // supersedes the previous tail, making it safe to emit.
        if #unavailable(iOS 18.0) {
            while finalizedSegmentEntries < max(0, events.count - 1) {
                emitSegmentMetric(events[finalizedSegmentEntries])
                finalizedSegmentEntries += 1
            }
        }
        if !hasSelectedAudio { refreshAudioSelection() }
    }

    // Emits the deferred last segment-metric entry. Called on natural end-of-playback and on detach
    // so the final segment is never silently dropped.
    private func flushLastSegmentEntry() {
        guard #unavailable(iOS 18.0),
              let events = player?.currentItem?.accessLog()?.events,
              finalizedSegmentEntries < events.count else { return }
        emitSegmentMetric(events[finalizedSegmentEntries])
        finalizedSegmentEntries += 1
    }

    private func emitSegmentMetric(_ event: AVPlayerItemAccessLogEvent) {
        let tsMs = event.playbackStartDate.map { Int64($0.timeIntervalSince1970 * 1000) } ?? nowMs()
        sink.addSegmentMetric(metric: AVAccessLogMappersKt.accessLogSegmentMetric(
            nowMs: tsMs,
            uri: event.uri,
            sizeBytes: event.numberOfBytesTransferred,
            observedBitrate: event.observedBitrate,
            transferDurationSeconds: event.transferDuration
        ))
    }

    private func emitBitrateSwitch(nowMs: Int64, indicatedBitrate: Double) {
        guard AVAccessLogMappersKt.isBitrateSwitch(previousIndicated: lastIndicatedBitrate, currentIndicated: indicatedBitrate) else { return }
        let newTrack = resolveActiveTrack(indicatedBitrate)
        let previousTrack = currentActiveTrack
        let reason: SwitchReason = previousTrack == nil ? .initial : .adaptive
        sink.addTrackSwitchEvent(event: TrackSwitchEventVideoSwitch(
            timestampMs: nowMs,
            bufferDurationMs: 0,
            reason: reason,
            previousTrack: previousTrack,
            newTrack: newTrack
        ))
        sink.updateActiveTrack(info: newTrack)
        currentActiveTrack = newTrack
        updateVariantSelection(newTrack.bitrate)
        lastIndicatedBitrate = indicatedBitrate
    }

    private func resolveActiveTrack(_ indicatedBitrate: Double) -> ActiveTrackInfo {
        let target = Int32(indicatedBitrate)
        if let variant = currentSnapshot?.variants.min(by: { abs($0.bitrate - target) < abs($1.bitrate - target) }) {
            return ActiveTrackInfo(bitrate: variant.bitrate, width: variant.width, height: variant.height, codecs: variant.codecs, id: variant.id)
        }
        return AVAccessLogMappersKt.activeTrackFromIndicatedBitrate(indicatedBitrate: indicatedBitrate)
    }

    private func updateVariantSelection(_ selectedBitrate: Int32) {
        guard let snapshot = currentSnapshot, !snapshot.variants.isEmpty else { return }
        let variants = snapshot.variants
        var bestIdx = 0
        for i in variants.indices where abs(variants[i].bitrate - selectedBitrate) < abs(variants[bestIdx].bitrate - selectedBitrate) {
            bestIdx = i
        }
        let updated = variants.enumerated().map { index, v in
            VariantInfo(bitrate: v.bitrate, width: v.width, height: v.height, codecs: v.codecs, frameRate: v.frameRate, id: v.id, isSelected: index == bestIdx)
        }
        let newSnapshot = TracksSnapshot(variants: updated, audioTracks: snapshot.audioTracks, subtitleTracks: snapshot.subtitleTracks)
        sink.updateTrackList(info: newSnapshot)
        currentSnapshot = newSnapshot
    }

    private func refreshAudioSelection() {
        guard let currentItem = player?.currentItem,
              let asset = currentItem.asset as? AVURLAsset,
              let snapshot = currentSnapshot,
              let group = asset.mediaSelectionGroup(forMediaCharacteristic: .audible),
              let selected = currentItem.currentMediaSelection.selectedMediaOption(in: group),
              let idx = group.options.firstIndex(where: { $0.isEqual(selected) }) else { return }
        if snapshot.audioTracks.indices.contains(idx), snapshot.audioTracks[idx].isSelected { return }
        let updatedAudio = snapshot.audioTracks.enumerated().map { index, t in
            AudioTrackInfo(language: t.language, label: t.label, codecs: t.codecs, bitrate: t.bitrate, channelCount: t.channelCount, sampleRate: t.sampleRate, isMuxed: t.isMuxed, id: t.id, isSelected: index == idx)
        }
        let newSnapshot = TracksSnapshot(variants: snapshot.variants, audioTracks: updatedAudio, subtitleTracks: snapshot.subtitleTracks)
        sink.updateTrackList(info: newSnapshot)
        currentSnapshot = newSnapshot
        let activeAudio = updatedAudio.first { $0.isSelected }
        sink.updateActiveAudioTrack(info: activeAudio)
        hasSelectedAudio = activeAudio != nil
    }

    private func onNewErrorLogEntry() {
        guard let events = player?.currentItem?.errorLog()?.events else { return }
        while processedErrorEntries < events.count {
            let event = events[processedErrorEntries]
            sink.addPlaybackError(event: AVAccessLogMappersKt.loadError(
                nowMs: nowMs(),
                errorDomain: event.errorDomain,
                statusCode: Int64(event.errorStatusCode),
                uri: event.uri,
                comment: event.errorComment
            ))
            processedErrorEntries += 1
        }
    }

    // Monotonic wall-clock: CACurrentMediaTime() never goes backward; epochOffsetMs bridges it to
    // epoch time (set once per attach so NTP adjustments don't affect intra-session timestamps).
    private func nowMs() -> Int64 { Int64(CACurrentMediaTime() * 1000) + epochOffsetMs }
}
