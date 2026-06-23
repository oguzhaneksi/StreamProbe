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
/// variants-load completion hops to main). Holds the player weakly — it observes, it does not own.
final class AVPlayerProbe {
    private let sink: DiagnosticsSink
    private weak var player: AVPlayer?
    private var active = false
    private var observers: [NSObjectProtocol] = []

    private var finalizedAccessEntries = 0
    private var processedErrorEntries = 0
    private var lastIndicatedBitrate = -1.0

    private var currentSnapshot: TracksSnapshot?
    private var currentActiveTrack: ActiveTrackInfo?
    private var hasSelectedAudio = false

    private var epochOffsetMs: Int64 = 0

    private let variantsKey = "variants"
    private let droppedFrameThreshold = 3

    init(sink: DiagnosticsSink) {
        self.sink = sink
    }

    func attach(player: AVPlayer) {
        detach()
        self.player = player
        active = true
        epochOffsetMs = Int64(Date().timeIntervalSince1970 * 1000) - Int64(CACurrentMediaTime() * 1000)
        registerLogObservers()
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
        // Flush finalized entries before teardown (manual-stop case; natural end is handled by the
        // DidPlayToEndTime observer).
        onNewAccessLogEntry(flushAll: true)
        active = false
        let center = NotificationCenter.default
        observers.forEach { center.removeObserver($0) }
        observers.removeAll()
        player = nil
        finalizedAccessEntries = 0
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
            self.onNewAccessLogEntry(flushAll: false)
        })
        observers.append(center.addObserver(forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main) { [weak self] _ in
            guard let self, self.active else { return }
            self.onNewErrorLogEntry()
        })
        observers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            guard let self, self.active else { return }
            self.onNewAccessLogEntry(flushAll: true)
        })
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

    private func onNewAccessLogEntry(flushAll: Bool) {
        guard let events = player?.currentItem?.accessLog()?.events else { return }
        let finalized = flushAll ? events.count : events.count - 1
        while finalizedAccessEntries < finalized {
            processAccessEntry(events[finalizedAccessEntries])
            finalizedAccessEntries += 1
        }
        if !hasSelectedAudio { refreshAudioSelection() }
    }

    private func processAccessEntry(_ event: AVPlayerItemAccessLogEvent) {
        let requestTimestampMs = event.playbackStartDate.map { Int64($0.timeIntervalSince1970 * 1000) } ?? nowMs()
        sink.addSegmentMetric(metric: AVAccessLogMappersKt.accessLogSegmentMetric(
            nowMs: requestTimestampMs,
            uri: event.uri,
            sizeBytes: event.numberOfBytesTransferred,
            observedBitrate: event.observedBitrate,
            transferDurationSeconds: event.transferDuration
        ))
        emitBitrateSwitch(nowMs: requestTimestampMs, indicatedBitrate: event.indicatedBitrate)
        if event.numberOfDroppedVideoFrames >= droppedFrameThreshold {
            sink.addPlaybackError(event: AVAccessLogMappersKt.droppedFramesError(
                nowMs: requestTimestampMs,
                droppedFrames: Int64(event.numberOfDroppedVideoFrames)
            ))
        }
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
