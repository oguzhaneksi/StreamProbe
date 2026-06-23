# iOS Two-Layer / SPM — Plan 2c: AVPlayerProbe Swift Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Kotlin `AVPlayerProbe` to Swift in the `StreamProbeIOS` package, writing diagnostics through the Core's write-only `DiagnosticsSink` and keeping local mirror state for the reads the sink doesn't expose; then wire it into `StreamProbe.attach(player:)`/`detach()`. After this plan the SDK produces live diagnostics on iOS entirely from Swift, feeding the shared Kotlin Core.

**Architecture:** Per `docs/superpowers/specs/2026-06-23-ios-two-layer-spm-packaging-design.md`. The pure mapping math stays in Kotlin (the public `AVMetricMappersKt`/`AVAccessLogMappersKt` helpers, already tested in `iosTest`); this plan adds the AVFoundation **observation** in Swift (the part that was hard to debug in Kotlin/Native). The probe is the sole writer on iOS, so it mirrors the small amount of state the Kotlin version read back from the store.

**Tech Stack:** Swift, AVFoundation, `NotificationCenter`, QuartzCore (`CACurrentMediaTime`), SKIE-bridged Core API.

## Global Constraints

- **Source of truth for behavior:** `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVPlayerProbe.kt` — replicate its logic exactly (variants async-load with stale-closure guard; access-log entries processed **one notification behind** via `events.count - 1`, flushed fully on detach / DidPlayToEndTime; bitrate-switch detection; closest-variant active-track resolution; variant-selection marking; lazy audio-selection refresh; per-entry dropped-frames ≥ 3; monotonic `nowMs()`).
- **Write-only sink + local mirrors:** the probe receives a `DiagnosticsSink` and never reads the store back. It keeps `currentSnapshot: TracksSnapshot?`, `currentActiveTrack: ActiveTrackInfo?`, and `hasSelectedAudio: Bool` mirrors — these replace the Kotlin reads of `store.trackListInfo.value` / `store.activeTrack.value` / `store.activeAudioTrack.value`.
- **Pure math via Core helpers:** all numeric/string mapping uses the public Kotlin helpers — `AVMetricMappersKt.{pickVariantBitrate,dimensionOrUnknown,frameRateOrUnknown,joinCodecs,fourCCToString,preferredLanguageTag,defaultSubtitleKind}` and `AVAccessLogMappersKt.{accessLogSegmentMetric,activeTrackFromIndicatedBitrate,isBitrateSwitch,droppedFramesError,loadError}`. Do **not** reimplement these in Swift.
- **Build immutable models via their initializers**, not via SKIE/K-N copy methods (e.g. to flip `isSelected`, reconstruct `VariantInfo(...)`).
- **The probe holds the `AVPlayer` weakly** (it is an observer, not an owner) — a deviation from the Kotlin strong ref, chosen so the probe never keeps the host's player alive; all callbacks `guard` on it.
- **Use `[weak self]` in every notification-observer closure** (the probe stores the observer tokens and removes them in `detach()`).
- **Deprecation warnings are expected and acceptable** for the iOS-15 deployment floor: `loadValuesAsynchronously(forKeys:completionHandler:)`, `statusOfValue(forKey:error:)`, `mediaSelectionGroup(forMediaCharacteristic:)`, and `Locale.languageCode` are deprecated in iOS 16 but are the available APIs at the iOS-15 base (their async replacements require iOS 16). Do **not** refactor to the async APIs in this plan; matching the Kotlin probe is the goal.
- **Verification = the iOS build** (`STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build` → BUILD SUCCEEDED). The probe is not unit-testable (it observes concrete AVFoundation classes) and **live verification is sandbox-blocked** (simulator TLS, per `sdk/src/iosMain/CLAUDE.md`) — so the automated gate is build-green + code review; live-verify on a real device is a follow-up outside this environment.
- **The Gradle gate is unaffected** (no Kotlin/Gradle change); iosApp remains known-red until Plan 3.
- **Commit per task** (authorized); **never `git push`** without explicit approval.

---

## File Structure

- `Sources/StreamProbeIOS/AVTrackMapping.swift` — **new.** Swift equivalents of the (Kotlin-internal, AVFoundation-typed) `mapVariant`/`mapAudioOption`/`mapLegibleOption`, built from the public Core helpers + model initializers.
- `Sources/StreamProbeIOS/AVPlayerProbe.swift` — **new.** The observation logic, writing via `DiagnosticsSink`.
- `Sources/StreamProbeIOS/StreamProbe.swift` — **modify.** Wire `attach(player:)`/`detach()` to the probe; apply the Plan 2b carryover doc fixes.

---

## Task 1: `AVTrackMapping.swift` — Swift variant/option mappers

**Files:**
- Create: `Sources/StreamProbeIOS/AVTrackMapping.swift`

**Interfaces:**
- Consumes: `AVAssetVariant`, `AVMediaSelectionOption` (AVFoundation); `AVMetricMappersKt.*` and the model initializers `VariantInfo`, `AudioTrackInfo`, `SubtitleTrackInfo` (StreamProbeCore).
- Produces (module-internal funcs): `mapVariant(_:) -> VariantInfo`, `mapAudioOption(_:isSelected:) -> AudioTrackInfo`, `mapLegibleOption(_:isSelected:) -> SubtitleTrackInfo`.

- [ ] **Step 1: Write `AVTrackMapping.swift`**

```swift
import AVFoundation
import CoreMedia
import StreamProbeCore

/*
 * Swift equivalents of the Kotlin-internal, AVFoundation-typed variant/option mappers. They extract
 * AVFoundation fields and delegate the numeric/string logic to the public Core helpers
 * (`AVMetricMappersKt`), then build the platform-neutral SDK models. The iOS gaps the Kotlin mappers
 * documented hold here too: audio channel/sample-rate unavailable, codec = bare FourCC, legible = SIDECAR.
 */

/// Maps an `AVAssetVariant` (iOS 15+) to a `VariantInfo`. `id`/`isSelected` are unknown up front.
func mapVariant(_ variant: AVAssetVariant) -> VariantInfo {
    let bitrate = AVMetricMappersKt.pickVariantBitrate(
        peakBitRate: variant.peakBitRate,
        averageBitRate: variant.averageBitRate
    )
    guard let video = variant.videoAttributes else {
        return VariantInfo(bitrate: bitrate, width: -1, height: -1, codecs: nil, frameRate: -1, id: nil, isSelected: false)
    }
    let size = video.presentationSize
    let codecs = AVMetricMappersKt.joinCodecs(
        codecTypes: video.codecTypes.map { AVMetricMappersKt.fourCCToString(value: Int32(bitPattern: $0)) }
    )
    return VariantInfo(
        bitrate: bitrate,
        width: AVMetricMappersKt.dimensionOrUnknown(value: Double(size.width)),
        height: AVMetricMappersKt.dimensionOrUnknown(value: Double(size.height)),
        codecs: codecs,
        frameRate: AVMetricMappersKt.frameRateOrUnknown(nominalFrameRate: Double(video.nominalFrameRate)),
        id: nil,
        isSelected: false
    )
}

/// Maps an audible `AVMediaSelectionOption` to an `AudioTrackInfo`. Channel/sample-rate are unavailable on iOS.
func mapAudioOption(_ option: AVMediaSelectionOption, isSelected: Bool) -> AudioTrackInfo {
    AudioTrackInfo(
        language: AVMetricMappersKt.preferredLanguageTag(
            extendedLanguageTag: option.extendedLanguageTag,
            localeLanguageCode: option.locale?.languageCode
        ),
        label: option.displayName.isEmpty ? nil : option.displayName,
        codecs: nil,
        bitrate: 0,
        channelCount: 0,
        sampleRate: 0,
        isMuxed: false,
        id: nil,
        isSelected: isSelected
    )
}

/// Maps a legible `AVMediaSelectionOption` to a `SubtitleTrackInfo`.
func mapLegibleOption(_ option: AVMediaSelectionOption, isSelected: Bool) -> SubtitleTrackInfo {
    SubtitleTrackInfo(
        language: AVMetricMappersKt.preferredLanguageTag(
            extendedLanguageTag: option.extendedLanguageTag,
            localeLanguageCode: option.locale?.languageCode
        ),
        label: option.displayName.isEmpty ? nil : option.displayName,
        mimeType: nil,
        kind: AVMetricMappersKt.defaultSubtitleKind(),
        id: nil,
        isSelected: isSelected
    )
}
```

- [ ] **Step 2: Smoke-build the package for iOS Simulator**

Run from the repo root (after ensuring the Release XCFramework exists — `./gradlew :sdk:assembleStreamProbeCoreReleaseXCFramework` if needed):
```bash
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build
```
Expected: **BUILD SUCCEEDED** (the mappers compile; the model initializers and `AVMetricMappersKt` argument labels resolve as written). A `Locale.languageCode` deprecation warning is expected and acceptable. If a model initializer label or a helper signature differs from the above, correct it to match the framework's actual Swift API and report the difference.

- [ ] **Step 3: Commit**

```bash
git add Sources/StreamProbeIOS/AVTrackMapping.swift
git commit -m "feat(ios): add Swift AVFoundation track mappers over Core helpers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `AVPlayerProbe.swift` — the observation port

**Files:**
- Create: `Sources/StreamProbeIOS/AVPlayerProbe.swift`

**Interfaces:**
- Consumes: `DiagnosticsSink` (Core), the `AVTrackMapping` funcs from Task 1, `AVAccessLogMappersKt.*`, the model types, and `SwitchReason`/`TrackSwitchEventVideoSwitch` (Core); AVFoundation (`AVPlayer`, `AVURLAsset`, access/error-log events, media selection), `NotificationCenter`, `CACurrentMediaTime`.
- Produces (module-internal): `final class AVPlayerProbe` with `init(sink: DiagnosticsSink)`, `func attach(player: AVPlayer)`, `func detach()`.

- [ ] **Step 1: Write `AVPlayerProbe.swift`**

```swift
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
```

- [ ] **Step 2: Smoke-build the package for iOS Simulator**

Run from the repo root:
```bash
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build
```
Expected: **BUILD SUCCEEDED** (deprecation warnings for `loadValuesAsynchronously`/`statusOfValue`/`mediaSelectionGroup` are expected). If `core` cannot be passed where `DiagnosticsSink` is expected in Task 3 because K/N did not bridge the protocol conformance, the fallback is to type the probe's dependency as `ProbeCore` instead (it exposes the same write methods) — but try `DiagnosticsSink` first. If any AVFoundation property name/type differs from the above (e.g. `numberOfBytesTransferred`, `errorStatusCode`), correct it to the real API and report the difference.

- [ ] **Step 3: Commit**

```bash
git add Sources/StreamProbeIOS/AVPlayerProbe.swift
git commit -m "feat(ios): port AVPlayerProbe to Swift (writes via DiagnosticsSink)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Wire the probe into `StreamProbe` + apply Plan 2b carryover doc fixes

**Files:**
- Modify: `Sources/StreamProbeIOS/StreamProbe.swift`

**Interfaces:**
- Consumes: `AVPlayerProbe(sink:)` from Task 2; `core` (`ProbeCore`, conforms to `DiagnosticsSink`).
- Produces: `StreamProbe.attach(player:)` now creates + attaches an `AVPlayerProbe`; `detach()` tears it down. Public signatures unchanged.

- [ ] **Step 1: Add the probe field and wire attach/detach**

In `Sources/StreamProbeIOS/StreamProbe.swift`, add a probe field after `private let core = ProbeCore()`:

```swift
    private var probe: AVPlayerProbe?
```

Replace the staged `attach(player:)` with the wired version (and update its doc), and update `detach()`:

```swift
    /// Attaches StreamProbe to `player`, clears the previous session, and starts observing
    /// AVFoundation. Call `show()` afterward to start the overlay's live updates.
    public func attach(player: AVPlayer) {
        core.clear()
        let probe = AVPlayerProbe(sink: core)
        self.probe = probe
        probe.attach(player: player)
    }
```

```swift
    /// Detaches: stops the AVFoundation observer, stops the presenter collectors, and clears the session.
    public func detach() {
        probe?.detach()
        probe = nil
        core.stop()
        core.clear()
    }
```

- [ ] **Step 2: Apply the Plan 2b carryover doc fix (must-retain)**

Strengthen the `makeOverlayWindow(windowScene:)` doc comment so the retention requirement is a hard "must" (a returned `UIWindow` with no strong reference silently deallocates). Change the sentence "The caller retains the returned window and controls its `isHidden`…" to:

```swift
    /// Creates the built-in always-on-top overlay window for `windowScene`, rendering this probe's
    /// diagnostics via `overlayPresenter`. The caller **must** retain the returned window (a detached
    /// `UIWindow` with no strong reference is silently deallocated) and controls its `isHidden` to
    /// show/hide the overlay UI (independent of `show()`/`hide()`, which control the live data feed).
```

(The other Plan 2b carryover — annotating `StreamProbe` `@MainActor` — is **intentionally deferred**: the probe's `NotificationCenter` closures and `AVPlayer` interaction make strict-concurrency annotation a separate change that would entangle this port. The main-thread contract remains documented in prose. Leave it for a dedicated concurrency pass.)

- [ ] **Step 3: Smoke-build the package for iOS Simulator**

Run from the repo root:
```bash
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build
```
Expected: **BUILD SUCCEEDED** — `attach` constructs `AVPlayerProbe(sink: core)` (with `core` accepted as `DiagnosticsSink`) and `detach` tears it down.

- [ ] **Step 4: Commit**

```bash
git add Sources/StreamProbeIOS/StreamProbe.swift
git commit -m "feat(ios): wire AVPlayerProbe into StreamProbe.attach/detach

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **SPM smoke build green:** `STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build` → **BUILD SUCCEEDED**. The full Swift layer (overlay + entry point + probe + mappers) compiles and links over the Core binary.

- [ ] **Gradle gate green (unchanged by this plan):**
```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```
Expected: **BUILD SUCCEEDED**.

- [ ] **Known limitations (not regressions):** (1) the probe's live behavior is **not** verified here — simulator TLS is sandbox-blocked, so verify on a real device/simulator with working networking as a follow-up; (2) deprecation warnings for the iOS-15 AVFoundation APIs are expected; (3) `iosApp/` still does not build — migrated in Plan 3.

---

## Subsequent Plan (deferred)

- **Plan 3 — Demo migration, Kotlin cleanup, CI.** Rewrite `iosApp/`'s `SceneDelegate` to `overlayWindow = probe.makeOverlayWindow(windowScene:)` (keeping the settings `isHidden` binding) and point the Xcode project at the local SPM package (XcodeGen `packages:` local path + `STREAMPROBE_LOCAL`); migrate `PlayerScreen`/`AppDependencies` to the Swift `StreamProbe` (replacing `StreamProbe_`); delete the now-superseded Kotlin `AVPlayerProbe.kt`, `StreamProbe.ios.kt`, the AVFoundation-typed mapper functions in `AVMetricMappers.kt`, and the `expect/actual StreamProbe` class (drop `-Xexpect-actual-classes`; Android gets a plain `androidMain` `StreamProbe` with identical public API); add `IOS_VERSION_NAME=0.1.0` + `publish-spm.yml` (clean runner; build Release XCFramework via `zipReleaseXCFramework`; emit checksum to a file; fill `Package.swift` url+checksum; tag `v0.1.0`; draft release). Live-verify the full overlay against a real stream on device. Carryovers from Plan 2a/2b reviews apply.
```
