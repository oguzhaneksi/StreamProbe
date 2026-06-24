# StreamProbe — Specification

This document describes the goals, scope, features, and technical design of StreamProbe. For a short overview, see [`README.md`](./README.md).

---

## 1. Problem Statement

StreamProbe is a **Kotlin Multiplatform** debug SDK targeting **Android (Media3/ExoPlayer)** and **iOS (AVFoundation/`AVPlayer`)**. The problem is framed below in Android terms because that platform shipped first and its instrumentation is the most complete; the iOS implementation tackles the same class of problem from a different observation surface (see §3.11 and §4.5). The portable diagnostics core is shared verbatim between both platforms.

Android streaming apps built on Media3/ExoPlayer routinely run into stream delivery issues that are hard to diagnose from inside the app:

- The player picks the wrong rendition on startup.
- An ABR switch happens at an unexpected moment.
- Segment latency spikes without an obvious cause.
- A CDN serves cache misses when hits were expected.

Today, diagnosing any of these requires assembling a toolchain by hand:

| Approach              | Cost                                                                         |
| --------------------- | ---------------------------------------------------------------------------- |
| Charles Proxy         | Device proxy setup, SSL cert install, manual traffic filtering.              |
| `adb logcat`          | Parsing raw ExoPlayer logs to find the relevant lines.                       |
| Manual manifest fetch | Copying URLs into a browser and reading `.m3u8` / MPD by eye.                |

Each cycle takes 15–30 minutes per investigation, depends on a tethered device, requires external tools, and the resulting workflow can't be handed off to a teammate. The information is also fragmented — timing lives in logcat, manifest contents in a browser tab, headers in Charles.

**StreamProbe's goal is to collapse that workflow into a single in-app overlay that activates the moment the SDK is attached.**

---

## 2. Non-Goals

StreamProbe is deliberately *not*:

- **An analytics or QoE platform.** No data is sent to a server. Everything stays on-device.
- **A player.** It attaches to an existing Media3/ExoPlayer setup; it does not replace it.
- **A production monitoring tool.** It is intended for development and QA builds only. Release builds exclude the SDK entirely.

---

## 3. Features

### 3.1 Track Enumeration

When the player's track selection changes, StreamProbe reads `player.currentTracks` (the Media3 `Tracks` API) and builds a protocol-agnostic `TracksSnapshot` containing:

- All video variants in the current track groups
- All audio renditions in the current track groups
- All subtitle / closed-caption renditions in the current track groups

Each track entry carries an `isSelected` flag set from `Tracks.Group.isTrackSelected(i)`, which the overlay reads directly — no secondary active-track comparison is needed. `VariantInfo` and `AudioTrackInfo` also carry a nullable `id` field (the Media3 `Format.id`) that is preferred over dimension/bitrate matching when building DiffUtil identity keys.

> **Protocol-agnostic:** This approach works identically for HLS, DASH, and any other source type ExoPlayer supports. No format-specific manifest parsing is required.

### 3.2 Variant and Rendition Listing

Every available video, audio, and subtitle rendition is enumerated and displayed in the **Tracks** tab, grouped into three sections: `VIDEO`, `AUDIO`, and `SUBTITLES`.

- **Video**: Bandwidth, Resolution, and Codec per variant.
- **Audio**: Language/label, channel layout, codec, bitrate, and sample rate per audio rendition. Muxed audio entries carry an `isMuxed` flag and a visual badge.
- **Subtitles**: Language/label and kind (`SIDECAR` for WebVTT/TTML, `CC` for closed captions) per subtitle rendition.

The currently selected rendition in each section is flagged with an active-dot indicator in real time. Active state is determined by the `isSelected` field on each track model, set from `Tracks.Group.isTrackSelected(i)` in `onTracksChanged` — no secondary comparison against a separate active-track `StateFlow` is required in the adapter.

### 3.3 Segment Download Metrics

Each segment request is recorded with:

- Request timestamp
- **TTFB (time to first byte)** — Best-effort estimate via a `DataSource.Factory` wrapper (`StreamProbe.wrapDataSourceFactory`). The `open()`-duration proxy is correlated to each media segment by request URI + byte position and shown as `~NNms` (the `~` marks it as an estimate). True per-phase DNS/connect/TLS breakdown requires per-stack adapters and is planned for a future milestone.
- Total download duration
- Segment size in bytes
- Computed throughput (bytes / total duration)

These metrics are retained for the session and are the primary input for spotting latency spikes and bandwidth starvation.

### 3.4 CDN Response Headers

Response headers for every segment request are captured. The overlay highlights:

- `Cache-Control`
- `X-Cache`, `X-Cache-Status`
- `Via`
- CDN-specific headers such as `CF-Cache-Status` (Cloudflare), `X-Amz-Cf-Pop` (CloudFront), `X-Served-By` (Fastly)

Cache hit/miss is flagged visually per request so the developer can quickly spot cold-cache patterns.

### 3.5 Track Switch Log

Every time the player changes any track — video quality, audio rendition, or subtitle selection — a `TrackSwitchEvent` record is created. The sealed interface has three subtypes:

- **`VideoSwitch`** — video quality change; records previous/new `ActiveTrackInfo`, buffer state, and selection reason.
- **`AudioSwitch`** — audio rendition change; records previous/new `AudioTrackInfo`, buffer state, and selection reason.
- **`SubtitleSwitch`** — subtitle selection change; `newTrack` is `null` when the user disables subtitles.

All subtypes carry `timestampMs`, `bufferDurationMs`, and `reason` (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`). Records are kept in a single chronological list (cap: 200) and rendered as a unified timeline in the **Switches** tab.

### 3.6 Debug Overlay

A draggable panel rendered on top of the host app via `Activity.addContentView()`. This approach keeps the overlay **entirely within the host app's own window**, so **no extra permissions** (e.g., `SYSTEM_ALERT_WINDOW` / `ACTION_MANAGE_OVERLAY_PERMISSION`) are required.

At a glance it shows:

- The currently active video rendition (or a loading state if the manifest is not yet available)
- The currently active audio rendition (language, codec, channel layout, bitrate)
- The currently active subtitle/CC track, or `Off` when disabled
- The most recent segment metrics
- Current CDN cache hit/miss state

A **filter chip row** inside the panel lets the developer switch between three views:

- **Tracks** (default) — all renditions grouped into `VIDEO`, `AUDIO`, and `SUBTITLES` sections, each with resolution/bitrate/codec or language/codec details and an active-dot indicator.
- **Segments** — per-segment download duration, size, throughput, and cache-status dot. The segment timeline is shown in the same overlay panel with no Activity transition, so the host player is never backgrounded.
- **Switches** — chronological list of every track switch event (`VID` / `AUD` / `SUB`, colour-coded), showing the from→to track details, buffer duration at the time of the switch, switch reason (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`), and a relative timestamp from the first event. Subtitle disable events appear as `SUB … → Off`.

A fourth view, **Errors**, is reachable via a `⚠ N` header indicator pill (hidden when no errors are present) and is described in §3.8.

#### Orientation-aware layout

- **Portrait** — 310 dp wide vertical stack: stats (Active Video Track, Active Audio Track, Active Subtitle Track, Latest Segment, CDN Status) → chip row → list.
- **Landscape** — 440 dp wide horizontal split: left column for stats, right column for chip row + list. The list height is capped at `screenHeight × 0.55`, clamped to `[200 dp, 360 dp]`.

For host apps that declare `android:configChanges` covering orientation, the panel rebuilds in place via `View.onConfigurationChanged`. For apps that don't, the Activity recreates and the new Activity's `show(this)` call re-adds the panel. The selected chip (`viewMode`) is preserved across both rebuild paths — including when the user is in the Errors view.

### 3.8 Background Error Tracking

Silent, non-fatal errors that ExoPlayer absorbs are captured and surfaced in a dedicated Errors view reachable from the overlay header. Five categories are tracked:

| Category | Trigger | Colour |
|---|---|---|
| `LOAD_ERROR` | `onLoadError` for `DATA_TYPE_MEDIA` or `DATA_TYPE_MANIFEST`; `wasCanceled=true` is filtered out | Red |
| `VIDEO_CODEC_ERROR` | `onVideoCodecError` | Orange |
| `DROPPED_FRAMES` | `onDroppedVideoFrames` with ≥ 3 frames dropped | Yellow |
| `AUDIO_SINK_ERROR` | `onAudioSinkError` | Purple |
| `AUDIO_CODEC_ERROR` | `onAudioCodecError` | Green |
| `DRM_ERROR` | `onDrmSessionManagerError`; dual-surfaced — also recorded in the DRM event timeline | Cyan |

**Header indicator** — a `⚠ N` pill appears in the overlay header as soon as the first error is captured. Tapping it switches the body to the Errors view; if the body is collapsed, it auto-expands first.

**Errors view** — replaces the chip row with a back/title/clear/share header and renders a chronological error timeline. Each row shows index, category dot, category label, one-line message, relative timestamp, and a `▾`/`▴` chevron that signals expand/collapse affordance. Tapping a row expands an inline detail container showing the full message, exception text, and absolute wall-clock timestamp.

**Dropped-frames dedup** — consecutive `DROPPED_FRAMES` events within a 5-second window are merged into a single entry. The merged entry updates its message to `"X frames dropped (N bursts)"`. The original `timestampMs` is preserved (stable DiffUtil identity; no flicker).

**Error cap** — 200 entries, matching `MAX_ABR_EVENTS`. Oldest entries are dropped at the cap boundary, except when the newest event merges into the existing last entry.

**Share export** — the Share button fires `Intent.ACTION_SEND` with a plain-text listing of all captured errors, formatted as `[StreamProbe] N errors` followed by one row per error. Each row includes the absolute wall-clock timestamp in `[HH:mm:ss.SSS]` format. When a `detail` field is present (e.g. exception text), it is appended on an indented second line.

### 3.7 Attach / Detach API

The SDK is gated behind calls with independent player and Activity lifecycles:

```kotlin
val probe = StreamProbe()

// In the player owner (e.g. ViewModel) — player-scoped:
probe.attach(player)   // wires PlayerInterceptor; no Activity needed

// In each Activity onCreate — Activity-scoped, lifecycle-aware:
probe.show(this)       // adds overlay; auto-hides on Activity onDestroy

// When the player is released:
probe.detach()         // tears down interceptor, clears session, hides overlay
```

`show(activity)` subscribes a `DefaultLifecycleObserver` that calls `hide()` automatically on `ON_DESTROY`, preventing stale Activity references if the host forgets to call `hide()`. Calling `show(this)` again on Activity recreation (config change) performs an idempotent replace — the old panel is removed and a fresh one is added for the new Activity.

---

### 3.9 Audio & Subtitle Track Monitoring

**Captured per session:**

- **`TrackListInfo` / `TracksSnapshot`** — a protocol-agnostic snapshot built from `player.currentTracks`. `TracksSnapshot` is the concrete implementation of the `TrackListInfo` sealed interface and holds:
  - `variants: List<VariantInfo>` — all video track groups, each with `bitrate`, `width`, `height`, `codecs`, `frameRate`, `id`, and `isSelected`.
  - `audioTracks: List<AudioTrackInfo>` — all audio track groups, including muxed audio (detected via container MIME type), each with `isSelected`.
  - `subtitleTracks: List<SubtitleTrackInfo>` — all subtitle/CC track groups, each with `isSelected`.

  The snapshot replaces the former `ManifestInfo` / `HlsManifestInfo` / `DashManifestInfo` hierarchy. Source is `player.currentTracks` (not HLS/DASH manifest fields), so the same code path handles all stream formats.

**Active track state (real-time `StateFlow`):**

| Field | Type | Description |
|---|---|---|
| `SessionStore.activeAudioTrack` | `StateFlow<AudioTrackInfo?>` | Currently rendering audio rendition |
| `SessionStore.activeSubtitleTrack` | `StateFlow<SubtitleTrackInfo?>` | Currently rendering subtitle/CC track; `null` when disabled |

Both flows are updated in `probeTracks()` when `onTracksChanged` fires.

**`TrackSwitchEvent` — unified sealed interface (replaces `AbrSwitchEvent`):**

```
sealed interface TrackSwitchEvent
  ├── VideoSwitch(timestampMs, bufferDurationMs, reason, previousTrack?, newTrack)
  ├── AudioSwitch(timestampMs, bufferDurationMs, reason, previousTrack?, newTrack)
  └── SubtitleSwitch(timestampMs, bufferDurationMs, reason, previousTrack?, newTrack?)
```

`SubtitleSwitch.newTrack` is `null` when the user disables subtitles. `reason` is the same `SwitchReason` enum used for video ABR switches (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`).

**Overlay representation:**

- **Summary panel** — two new rows: "AUDIO" (`formatActiveAudio()`) and "SUBTITLE" (`formatActiveSubtitle()`) between the active video track row and the latest segment row.
- **Tracks tab** — the former "Variants" tab is now "Tracks". Renditions are grouped into three sections (`VIDEO`, `AUDIO`, `SUBTITLES`) via `RenditionListAdapter`. An active-dot indicator marks the currently selected rendition in each section.
- **Switches tab** — the former "ABR" tab is now "Switches". `SwitchTimelineAdapter` renders all `TrackSwitchEvent` subtypes with colour-coded type labels: `VID` (blue), `AUD` (green), `SUB` (purple).

---

### 3.10 DRM Monitoring

Tracks DRM session lifecycle events for Widevine, PlayReady, and ClearKey streams via a dedicated `DrmSessionTracker` `AnalyticsListener` registered alongside the main `PlayerInterceptor`.

**Captured events (`DrmSessionEvent` sealed interface):**

| Subtype | Trigger | Colour |
|---|---|---|
| `SessionAcquired` | `onDrmSessionAcquired` | Blue |
| `KeysLoaded` | `onDrmKeysLoaded` | Green |
| `SessionReleased` | `onDrmSessionReleased` | Grey |
| `SessionError` | `onDrmSessionManagerError` | Cyan |

All subtypes carry `id` (monotonic, stable DiffUtil key), `timestampMs`, and `scheme` (`WIDEVINE` / `PLAYREADY` / `CLEARKEY` / `UNKNOWN`). `KeysLoaded` additionally carries `licenseLatencyMs` — the wall-clock delta from `onDrmSessionAcquired` to `onDrmKeysLoaded`. Latency may be inflated on key rotation (informational).

**Scheme detection** — resolved from `eventTime.timeline.getWindow(...).mediaItem.localConfiguration?.drmConfiguration?.scheme` rather than `player.currentMediaItem` to correctly handle playlist transitions.

**`DrmStatusInfo`** — a live summary object (`scheme`, `state`, `lastLicenseLatencyMs`) written to `SessionStore.currentDrmState` on each event. Drives the overlay summary row.

**Overlay representation:**

- **Summary panel** — a `DRM` section label and status row (e.g. `Widevine  ·  Keys Loaded  ·  312ms`) appear between the subtitle row and the latest-segment row. Both are hidden (`GONE`) for clear streams and shown as soon as the first DRM event is recorded.
- **DRM chip** — a fourth filter chip (`DRM`) appears in the chip row for DRM streams; hidden when no events exist. Selecting it shows the DRM event timeline.
- **DRM tab** — chronological list of `DrmSessionEvent` entries rendered by `DrmTimelineAdapter`. Each row shows: index, colour-coded event dot, scheme badge (`WV` / `PR` / `CK` / `DRM`), event label, license latency (for `KeysLoaded` only), and relative timestamp.
- **Dual surface for errors** — `SessionError` is written to both `SessionStore.drmSessionEvents` (DRM tab) and `SessionStore.playbackErrors` (Errors tab as `DRM_ERROR`). There is no duplication; a single write path produces both entries.

**Landscape panel width** — increased from 440 dp to 540 dp to accommodate the additional DRM chip without wrapping.

**Event cap** — 200 entries (`MAX_DRM_EVENTS`), matching the error and switch caps.

---

### 3.11 iOS Support & Cross-Platform Parity

StreamProbe is a Kotlin Multiplatform SDK. The diagnostics core — `SessionStore`, all model types, `OverlayPresenter`, formatters, parsers, and registries — lives in `commonMain` and is compiled into both the Android library and a Kotlin **`StreamProbeCore`** binary for iOS. The overlay is rendered natively per platform (Android Views, iOS UIKit) but driven by the same shared `OverlayPresenter`, so layout, chip navigation, expandable rows, drag, auto-scroll, orientation handling, and share are at **feature parity**.

On iOS the player adapter is a native **Swift** layer (`AVPlayerProbe`) that observes AVFoundation — `AVPlayerItem` access-log and error-log notifications, KVO, and `AVAssetVariant` discovery — extracts primitive fields, and feeds them through the Core's pure mapping helpers and a narrow write sink into the same `SessionStore`. The architecture is detailed in §4.5.

**iOS feature deltas.** AVFoundation exposes a coarser surface than Media3's analytics/HTTP hooks, so some features differ on iOS:

| Feature | Android | iOS |
|---|---|---|
| Track enumeration (video / audio / subtitle) | ✅ `player.currentTracks` | ✅ `AVAssetVariant` + media selection |
| Active track + switch timeline | ✅ per-format callbacks | ✅ derived from access log / variant changes |
| Segment metrics | ✅ true per-segment (`onLoadCompleted`) | ⚠️ **aggregate** roll-ups from the access log, badged **`AGG`** |
| CDN cache headers (hit/miss) | ✅ HTTP response headers | ❌ not exposed by `AVPlayer` |
| TTFB estimate (`~NNms`) | ✅ `DataSource.Factory` wrapper | ❌ no per-request timing surface |
| Background error tracking | ✅ load/codec/frames/sink errors | ✅ error-log notifications |
| DRM monitoring | ✅ Widevine / PlayReady / ClearKey | ❌ FairPlay deferred (see §6) |

Aggregate segment metrics are badged `AGG` so the cardinality difference is honest in the UI rather than implied to be per-segment.

---

## 4. Technical Design

### 4.1 Interception Points

StreamProbe instruments a single layer via a standard Media3 `AnalyticsListener` wired to the player:

- **`onTracksChanged`** — calls `probeTracks()` which iterates `player.currentTracks` and builds a `TracksSnapshot` containing all video, audio, and subtitle track groups with `isSelected` set per track. The snapshot is pushed to `SessionStore.trackListInfo`. `probeTracks()` also updates `activeAudioTrack` / `activeSubtitleTrack` state flows and emits a `SubtitleSwitch(newTrack = null)` when a previously selected subtitle track is disabled.
- **`onVideoInputFormatChanged`** — the authoritative source for `VideoSwitch` events. Emits a `TrackSwitchEvent.VideoSwitch` using the selection reason cached by `onDownstreamFormatChanged`, then resets the pending reason to `INITIAL`.
- **`onDownstreamFormatChanged`** — for `TRACK_TYPE_VIDEO` / `TRACK_TYPE_DEFAULT` (with valid dimensions): caches the selection reason in `pendingVideoSwitchReason` for use in `onVideoInputFormatChanged`. For `TRACK_TYPE_AUDIO` / `TRACK_TYPE_TEXT`: emits `AudioSwitch` / `SubtitleSwitch` events directly. `TRACK_TYPE_DEFAULT` events without valid video dimensions are ignored to avoid overwriting the pending reason with non-video (e.g., muxed audio) events.
- **`onLoadCompleted`** — captures per-segment download duration, byte count, computed throughput, and HTTP response headers (CDN cache hit/miss). Works regardless of the underlying HTTP stack.
- **`onLoadError`** — captures segment and manifest HTTP errors; cancellations (`wasCanceled=true`) are filtered out.
- **`onVideoCodecError`** — captures hardware/software codec failures.
- **`onDroppedVideoFrames`** — captures dropped-frame bursts ≥ 3 frames with dedup logic.
- **`onAudioSinkError`** — captures audio sink failures.
- **`onAudioCodecError`** — captures audio codec failures.

The following callbacks are handled by `DrmSessionTracker`, registered as a second `AnalyticsListener`:

- **`onDrmSessionAcquired`** — records a `SessionAcquired` event; detects the DRM scheme from `eventTime.timeline`; sets `currentDrmState` to the mapped `DrmSessionState`.
- **`onDrmKeysLoaded`** — records a `KeysLoaded` event with license latency (wall-clock delta from acquire); updates `currentDrmState` to `OPENED_WITH_KEYS`.
- **`onDrmSessionReleased`** — records a `SessionReleased` event; resets per-session state (`currentDrmScheme`, `lastDrmAcquireTimestampMs`); clears `currentDrmState`.
- **`onDrmSessionManagerError`** — records a `SessionError` in the DRM timeline and simultaneously adds a `DRM_ERROR` `PlaybackErrorEvent` to the errors list (dual surface).

All callbacks feed a single thread-safe in-memory `SessionStore`, which the overlay reads from via `StateFlow`.

**Baseline TTFB (M9 — shipped):** A `DataSource.Factory` wrapper (`TimingDataSourceFactory`) times `open()` duration as a best-effort TTFB proxy. Entries are keyed by request URI + byte position in a bounded `NetworkTimingRegistry` and consumed in `onLoadCompleted` (guaranteed happen-before on the success path). The lookup always uses `loadEventInfo.dataSpec.uri` (the pre-redirect request URI), never `loadEventInfo.uri` (post-redirect), to correctly correlate CDN-redirected streams.

**Planned (future milestone):** A `NetworkInspector` abstraction (OkHttp, Cronet, and HttpEngine adapters) to enable true per-phase DNS/connect/TLS breakdown beyond the `open()`-duration proxy.

### 4.2 Build & Dependency Strategy

StreamProbe is distributed as a regular `implementation` dependency. The SDK's public contract relies on explicit `attach(player)` / `detach(player)` calls; using `debugImplementation` would cause **compilation failures in release builds** whenever those call sites are referenced, because the symbols would not exist.

Instead, debug/release gating is the **host app's responsibility**:

```kotlin
// Option A — BuildConfig guard
if (BuildConfig.DEBUG) {
    StreamProbe.attach(player)
}

// Option B — No-op stub artifact (planned)
implementation("com.example:streamprobe:x.y.z")        // real SDK
releaseImplementation("com.example:streamprobe-noop:x.y.z")  // no-op stub
```

This approach:

- Keeps compilation safe across all build variants.
- Preserves the existing `attach` / `detach` contract without requiring no-op wrappers inside the SDK itself.
- Lets the host app choose its own strategy (build-config guard, flavor-based dependency, proguard strip, etc.).

A no-op stub artifact may be published alongside the real SDK to simplify Option B. *(To be finalized during M0.)*

### 4.3 Target Platforms

| | Android | iOS |
|---|---|---|
| **OS** | Android 6.0+ (API 23) | iOS 15.0+ |
| **Core language** | Kotlin (shared `commonMain`) | Kotlin core (`StreamProbeCore` binary) + Swift adapter/UI |
| **Player** | Media3 ExoPlayer | AVFoundation `AVPlayer` |
| **HTTP stack** | OkHttp (built-in), Cronet/HttpEngine (planned) | AVFoundation-internal (not directly observable) |
| **Overlay rendering** | `Activity.addContentView()` — no `SYSTEM_ALERT_WINDOW` permission | Separate root `UIWindow` at `.alert + 1` (hit-test passthrough) |

### 4.4 Distribution

- **Android**: Maven Central — `io.github.oguzhaneksi:streamprobe` (version from `VERSION_NAME` in `gradle.properties`), published via `vanniktech/maven-publish` on `release/v*` tags.
- **iOS**: Swift Package Manager — a checked-in `Package.swift` at the repo root exposing a `StreamProbe` product. A binary target (`StreamProbeCore` XCFramework, hosted as a GitHub Release zip) plus a Swift source target. Versioned independently via `IOS_VERSION_NAME` (starting at `0.1.0`) on plain `vX.Y.Z` tags, which SPM recognizes and Maven's `release/v*` tags do not collide with.
- **Future**: A no-op stub artifact (`streamprobe-noop`) to simplify release-build exclusion without `BuildConfig` guards.

### 4.5 Kotlin Multiplatform Architecture

The `sdk/` module is a single KMP module (`kotlin("multiplatform")` + `com.android.kotlin.multiplatform.library`) with Android and iOS (`iosArm64`, `iosSimulatorArm64`, `iosX64`) targets.

- **`commonMain`** — the portable, shared brain: models + enums, `SessionStore`, `NetworkTimingRegistry`, `CdnHeaderParser`, the overlay formatters, and the `presenter/` package (`OverlayPresenter` → `StateFlow<OverlayViewState>`). No `java.`/`android.` imports. `displayLanguage(tag)` is an `expect fun` with platform `actual`s. Locked by `OverlayPresenterTest` (commonTest, run under both Android and iOS test tasks).
- **`androidMain`** — Media3 adapters (`PlayerInterceptor`, `DrmSessionTracker`), the overlay Views, and a plain `StreamProbe` class with the existing public API. **Byte-identical** to the pre-migration public surface.
- **`iosMain`** — iOS-only Kotlin exported to the framework: the `NSLocale`-backed `displayLanguage` actual (the only remaining Kotlin/Native cinterop), pure primitive-typed mapping helpers consumed by the Swift layer, a `ProbeCore` holder (bundles the `internal` `SessionStore`, the public `OverlayPresenter`, and the show/hide coroutine lifecycle), and a narrow `DiagnosticsSink` write interface. Because all source sets compile into one module, `iosMain` calls `SessionStore`'s `internal` writes directly and re-exposes only a narrow `public` surface.

**iOS two-layer packaging.** The Kotlin core compiles to a static **`StreamProbeCore`** XCFramework (the binary SPM target). A native **Swift `StreamProbe`** layer (`Sources/StreamProbe/`) owns all AVFoundation I/O (`AVPlayerProbe`) and the overlay UI (UIKit), writes diagnostics through the Core sink, and observes `core.presenter.viewState`. [SKIE](https://skie.touchlab.co/) bridges Kotlin `StateFlow` → Swift `AsyncSequence` and sealed interfaces (`OverlayRow`, `TrackSwitchEvent`, `DrmSessionEvent`, `ViewMode`) → exhaustive Swift enums. Consumers `import StreamProbe`; the Core types are re-exported via `@_exported`. Only the AVFoundation **observation + field extraction** is Swift — the store, presenter, and mapping math stay in Kotlin.

> The earlier Kotlin/Native `AVPlayerProbe` (Phase 3 feasibility proof) and the `expect/actual StreamProbe` **class** were removed in favour of this two-layer split; this also eliminated the project's only expect/actual class and dropped the Beta `-Xexpect-actual-classes` compiler flag (`displayLanguage` remains a stable `expect fun`).

---

## 5. Comparison to ExoPlayer's Built-in Debug Tools

ExoPlayer ships with two debug helpers:

- **`EventLogger`** — logs player events (state changes, track selections, errors) to logcat.
- **`DebugTextViewHelper`** — renders basic player state into a `TextView`.

Both are useful but operate strictly at the player-event layer. Neither surfaces:

- Parsed manifest contents
- CDN response headers
- Segment-level HTTP timing
- A structured, navigable view of ABR history

StreamProbe closes that gap by sitting one layer below the player events and one layer above the raw HTTP stack.

---

## 6. Open Questions

The following are intentionally left open and will be resolved as the milestones progress:

- Exact shape of the no-op release artifact (stub module vs. empty classes vs. compile-only).
- Overlay interaction model beyond drag (resize, minimize, snap-to-edge).
- Export format for session data (JSON dump, shareable file) — planned post-M5.
- Auto-detection heuristic for the active network stack vs. explicit adapter selection by the host app (relevant once `NetworkInspector` adapters are built).
- **FairPlay DRM on iOS** — DRM lifecycle monitoring is Android-only today (Widevine/PlayReady/ClearKey via Media3's `DrmSessionManager`). iOS FairPlay support is planned for evaluation in a **future phase**; it is gated on external FairPlay license/key-server infrastructure needed to meaningfully observe and verify the session lifecycle. Open: which AVFoundation surface (`AVContentKeySession` delegate callbacks) yields a parity timeline, and how to test it without production key infra.
- **iOS per-segment metrics** — whether AVFoundation can be coaxed into finer-grained-than-access-log segment timing (e.g. via a custom `AVAssetResourceLoaderDelegate`) to narrow the gap with Android's true per-segment data, or whether the `AGG` roll-up remains the honest ceiling.