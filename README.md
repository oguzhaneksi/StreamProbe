# StreamProbe

A **Kotlin Multiplatform** debug SDK for **Android and iOS** apps that inspects HLS and DASH streaming in real time — on top of Media3/ExoPlayer on Android and AVFoundation/`AVPlayer` on iOS — surfacing track variants, segment metrics, CDN headers, ABR/track switches, DRM lifecycle, and playback errors through an in-app **draggable overlay on both platforms**.

https://github.com/user-attachments/assets/471e76d8-6653-4116-ae1d-0e1f3f023234

> ⚠️ **Development-only tool.** StreamProbe is designed for debug builds and should be omitted from release builds by gating it behind a host-managed mechanism (`BuildConfig.DEBUG` on Android, a `#if DEBUG` compilation condition on iOS). It is not an analytics, QoE, or production monitoring product.

---

## Requirements

Before you begin, ensure your project meets the following requirements:

**Android**
- **Minimum SDK**: Android 6.0 (API level 23) or higher.
- **Media3/ExoPlayer**: Version `1.10.0` or higher.
- **Kotlin**: Version `1.9.0`+ (currently built against `2.3.20`).

**iOS**
- **Minimum deployment target**: iOS 15.0 or higher.
- **Player**: AVFoundation `AVPlayer`.
- **Distribution**: Swift Package Manager (Xcode 15+ / Swift 5.9+).

---

## Why This Exists

When something goes wrong with stream delivery — the wrong rendition gets picked, an unexpected ABR switch happens, segment latency spikes, or a CDN cache miss slips through — the developer's options today are all awkward, and the problem looks the same whether the app runs on Android/ExoPlayer or iOS/AVPlayer:

- **A proxy** (Charles, Proxyman): wire the device through a proxy, install an SSL cert, filter traffic by hand.
- **Platform logs**: sift through raw player logs — `adb logcat` (ExoPlayer) on Android, Console.app / `os_log` and `AVPlayerItemAccessLog` dumps on iOS — looking for the relevant lines.
- **Manual manifest inspection**: copy the URL into a browser and read the `.m3u8` / MPD by eye.
- Usually, all three at once.

Each round of this takes 15–30 minutes, depends on a tethered device, requires external tools, and can't be handed off to a teammate. StreamProbe pulls the whole workflow into the app itself: the moment the SDK is attached, everything is captured automatically and readable through an on-screen overlay — on **both** platforms.

The platforms' own debug helpers don't close this gap. ExoPlayer's `EventLogger` / `DebugTextViewHelper` and iOS's `AVPlayerItemAccessLog` / `AVPlayerItemErrorLog` only surface player-level events and aggregate playback stats — they don't touch manifest contents, CDN headers, or per-segment timing. StreamProbe sits one layer below the player events and one above the raw transport to fill it.

---

## High-Level Architecture

StreamProbe is built around a single **shared diagnostics core** (Kotlin `commonMain`) that both platforms feed and read from. Each platform contributes only a thin **observation adapter** — which maps native player events into SDK models — and a **native overlay renderer**. Everything in between is shared verbatim.

```text
        Android (debug)                            iOS (debug)
┌────────────────────────────┐          ┌────────────────────────────┐
│  Media3 / ExoPlayer        │          │  AVFoundation / AVPlayer    │
│  AnalyticsListener         │          │  AVPlayerProbe (Swift):     │
│  (PlayerInterceptor):      │          │  access/error-log + KVO +   │
│  maps events → SDK models  │          │  AVAssetVariant discovery   │
└──────────────┬─────────────┘          └──────────────┬─────────────┘
               │  diagnostics                          │  diagnostics
               └──────────────────┬───────────────────┘  (via Core sink)
                                  ▼
   ┌──────────────────────────────────────────────────────────┐
   │           StreamProbe shared core  (commonMain)            │
   │                                                            │
   │   SessionStore      — in-memory StateFlow session store    │
   │   OverlayPresenter  → StateFlow<OverlayViewState>          │
   │   models · formatters · parsers · timing registries        │
   └────────────────────────────┬───────────────────────────────┘
                                │  view state (reads)
               ┌────────────────┴───────────────────┐
               ▼                                     ▼
   ┌────────────────────────┐            ┌────────────────────────┐
   │  Debug Overlay — Android│            │  Debug Overlay — iOS    │
   │  Views (addContentView) │            │  UIKit (own UIWindow)   │
   └────────────────────────┘            └────────────────────────┘
                                │
                                ▼
                       HLS / DASH origin + CDN
```

### Shared core

A single in-memory `SessionStore` (`StateFlow`-based) holds the captured session; `OverlayPresenter` projects it into a render-ready `OverlayViewState`. Both live in `commonMain` and are **identical across platforms** — only the adapter that fills the store and the renderer that draws the overlay differ. StreamProbe is distributed as a standard dependency; host apps guard `attach()` / `show()` behind a debug flag (`BuildConfig.DEBUG` on Android, `#if DEBUG` on iOS) for zero overhead in release builds.

### Android adapter

A single Media3 `AnalyticsListener` (`PlayerInterceptor`) wired to the player feeds the shared store:

- **Track snapshot** — built from `player.currentTracks` on `onTracksChanged`; all video variants, audio renditions, and subtitle tracks are enumerated into a protocol-agnostic `TracksSnapshot` (works identically for HLS and DASH). Each track carries an `isSelected` flag that drives the active-dot indicator in the overlay without secondary comparison.
- **Segment metrics and CDN headers** — captured on `onLoadCompleted`; per-segment download duration, size, throughput, and HTTP response headers (including cache hit/miss status) are stored for the session.
- **Track switch events** — `VideoSwitch` events are emitted from `onVideoInputFormatChanged` (the decoder-level format change is the authoritative signal); audio and subtitle switch events are emitted from `onDownstreamFormatChanged`. Every video quality change, audio rendition switch, and subtitle selection (including disable) is recorded as a `TrackSwitchEvent` (`VideoSwitch` / `AudioSwitch` / `SubtitleSwitch`) with the previous/new track, buffer state at switch time, and the reason Media3 reports (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`). Events are kept in a capped chronological list and displayed in the overlay's Switches tab.

### iOS adapter

On iOS the adapter is a native **Swift `AVPlayerProbe`** that observes AVFoundation — `AVPlayerItem` access/error-log notifications, KVO, and `AVAssetVariant` discovery — extracts primitive fields, and writes them into the shared store through the Core's pure mappers and a narrow write sink. iOS ships as a **two-layer package**: a Kotlin **`StreamProbeCore`** binary (the shared core, compiled to a static XCFramework) plus the Swift **`StreamProbe`** layer that owns all AVFoundation I/O and renders the same overlay in UIKit. [SKIE](https://skie.touchlab.co/) bridges the Kotlin `StateFlow`/sealed types to idiomatic Swift `AsyncSequence`/enums.

```text
iOS data flow
  AVPlayer ─ NSNotificationCenter / KVO / AVAssetVariant   (Swift: AVPlayerProbe)
     → extract primitive fields (Swift)
     → StreamProbeCore pure mappers + write sink (Kotlin)  → SessionStore
     → OverlayPresenter (Kotlin)                           → StateFlow<OverlayViewState>
     ─ (SKIE AsyncSequence) ─→ OverlayHostViewController (Swift / UIKit)
     → StreamProbeOverlayWindow (separate UIWindow at .alert+1)
```

Consumers `import StreamProbe` and get the Swift entry point plus (via `@_exported`) the shared Core types. The overlay ships **inside the package**, so iOS consumers get the full draggable panel out of the box — at parity with Android. Because AVFoundation exposes a coarser surface than Media3, a few features differ on iOS; see [Known Limitations](#known-limitations).

---

## Installation

StreamProbe is distributed via Maven Central. Add the dependency to your app-level `build.gradle.kts` file:

```kotlin
dependencies {
    // Add the core StreamProbe SDK
    implementation("io.github.oguzhaneksi:streamprobe:<version_name>")
}
```

### iOS (Swift Package Manager)

Add StreamProbe via Xcode's **File ▸ Add Package Dependencies…** or in your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/oguzhaneksi/StreamProbe.git", from: "0.1.0")
],
targets: [
    .target(
        name: "YourApp",
        dependencies: [.product(name: "StreamProbe", package: "StreamProbe")]
    )
]
```

Then `import StreamProbe` — you get the Swift entry point plus (via `@_exported`) the Core types. iOS is versioned independently of the Android (Maven Central) release.

*Note: Even though it is an `implementation` dependency, we recommend gating the SDK activation behind debug checks in the usage step to ensure it remains a development-only tool without compilation errors in release builds.*

---

## Usage

### Android

Integrating StreamProbe into your existing Media3/ExoPlayer setup requires only a few lines of code.

#### 1. Initialize and Attach to the Player

Create an instance of `StreamProbe` and attach it right after building your player:

```kotlin
val streamProbe = StreamProbe()

// Inside your ViewModel, PlayerManager, or Activity where the player is created:
val player = ExoPlayer.Builder(context).build()

if (BuildConfig.DEBUG) {
    streamProbe.attach(player)
}
```

#### 1b. Enable TTFB Measurement (Optional)

To capture per-segment TTFB estimates in the Segments tab, wrap your `DataSource.Factory` before passing it to `DefaultMediaSourceFactory`. This must be the outermost wrapper:

```kotlin
if (BuildConfig.DEBUG) {
    val baseFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(streamProbe.wrapDataSourceFactory(baseFactory))
    // Use mediaSourceFactory when building ExoPlayer
}
```

Gate this behind `BuildConfig.DEBUG` in release-bound hosts.

#### 2. Show the Overlay in your Activity

In your Activity's `onCreate()`, call `show(activity)` to attach the draggable debug overlay to the view hierarchy. `show()` requires a `ComponentActivity` (which covers `AppCompatActivity` and Jetpack Compose's `ComponentActivity`):

```kotlin
// Works with AppCompatActivity, ComponentActivity, etc.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // UI setup (Compose or XML)
        
        if (BuildConfig.DEBUG) {
            streamProbe.show(this)
        }
    }
}
```
*Note: The overlay is lifecycle-aware and will automatically clean itself up when the Activity is destroyed. You don't need any special system overlay permissions; it runs entirely within your app's window.*

#### 3. Cleanup

When your player is released, don't forget to detach the probe to avoid memory leaks:

```kotlin
if (BuildConfig.DEBUG) {
    streamProbe.detach()
}
player.release()
```

### iOS

The iOS API mirrors Android. Create a `StreamProbe`, attach it to your `AVPlayer`, install the built-in overlay window, and detach when you tear the player down. Gate everything behind `#if DEBUG`.

```swift
import StreamProbe
import AVFoundation

final class PlayerViewController: UIViewController {
    private let probe = StreamProbe()
    private var overlayWindow: UIWindow?   // must be retained
    private var player: AVPlayer!

    override func viewDidLoad() {
        super.viewDidLoad()
        player = AVPlayer(url: streamURL)

        #if DEBUG
        probe.attach(player: player)   // starts AVFoundation observation
        probe.show()                   // starts live overlay updates
        if let scene = view.window?.windowScene {
            // Retain the returned window — a detached UIWindow with no strong
            // reference is silently deallocated.
            overlayWindow = probe.makeOverlayWindow(windowScene: scene)
        }
        #endif
    }

    deinit {
        #if DEBUG
        probe.detach()                 // stops observation, clears the session
        overlayWindow = nil
        #endif
    }
}
```

`show()` / `hide()` toggle the live data feed; the overlay window's `isHidden` controls UI visibility independently. To drive a custom renderer instead of the built-in window, observe `probe.overlayPresenter.viewState` (a SKIE `AsyncSequence`) directly. Call all methods from the main thread.

---

## Debug Overlay Details

The overlay is rendered natively on each platform (Android Views, iOS UIKit) but driven by the same shared `OverlayPresenter`, so layout and behaviour are at parity. The sections below describe the full feature set; see [Known Limitations](#known-limitations) for the iOS-specific deltas (aggregate segment metrics, no per-segment CDN headers/TTFB, and DRM not yet supported on iOS).

Once attached, the StreamProbe overlay appears as a draggable panel. It displays the **currently active video track**, **active audio track**, **active subtitle track**, **DRM status** (scheme · state · license latency, hidden for clear streams), the **latest segment latency**, and the **CDN Cache state** (Hit/Miss) at the top.

Four filter-chip views (Tracks / Segments / Switches / DRM) plus a dedicated **Errors view** reachable from the header indicator provide a complete picture of what the player is doing at any moment. The DRM chip is hidden automatically when no DRM events have been recorded.

### 1. Tracks
This view displays all parsed renditions from the HLS `.m3u8` or DASH `.mpd` manifest, grouped into three sections: **VIDEO**, **AUDIO**, and **SUBTITLES**.
- **Video**: Bandwidth, Resolution, and Codec for every video rendition.
- **Audio**: Language, channel layout, codec, and bitrate for every audio rendition. Muxed audio entries are marked with a `muxed` badge.
- **Subtitles**: Language and type (WebVTT, TTML, CEA-608/708 CC) for every subtitle/caption rendition.
- The currently active rendition in each section is highlighted with an active-dot indicator in real-time.

### 2. Segments
Tracks the actual segment downloads as they happen. Each list item represents a segment chunk:
- **Download Duration & Size**: See exactly how long a chunk took to fetch and its payload size.
- **Throughput**: Calculated bandwidth (Bytes / Duration) for that specific segment.
- **TTFB**: Best-effort time-to-first-byte estimate (shown as `~NNms`). Requires wrapping the `DataSource.Factory` via `StreamProbe.wrapDataSourceFactory` — rows without a correlated timing entry show nothing.
- **CDN Status**: A color-coded indicator (Green for Cache Hit, Red/Yellow for Cache Miss) based on captured CDN headers like `X-Cache`, `CF-Cache-Status`, or `X-Amz-Cf-Pop`.

The segment list is capped at **500 entries**; older entries are dropped automatically as new ones arrive.

### 3. Switches
A chronological timeline of every track switch ExoPlayer makes during the session, covering **video**, **audio**, and **subtitle** changes. Each entry is colour-coded by type: `VID` (blue), `AUD` (green), `SUB` (purple).
- **From → To**: The exact track properties before and after the switch. For subtitle disable events, the "To" side shows `Off`.
- **Switch Reason**: Indicates *why* the player changed tracks (e.g., `ADAPTIVE` due to network conditions, `MANUAL` by user selection, `INITIAL` on startup).
- **Buffer State**: Shows how many seconds of buffer were left when the switch occurred, which helps debug panic-driven drops.

The Switches log is capped at **200 entries**; older events are dropped as new ones arrive.

### 4. DRM

A chronological timeline of DRM session lifecycle events for protected streams. The tab and its summary row are hidden automatically for clear (non-DRM) streams and appear as soon as the first DRM event is recorded.

- **Event types and colour coding**: `Session Acquired` (blue), `Keys Loaded` (green), `Session Released` (grey), `Error` (cyan).
- **Scheme badge**: each row shows a compact scheme label — `WV` (Widevine), `PR` (PlayReady), `CK` (ClearKey), or `DRM` (unknown).
- **License latency**: `Keys Loaded` rows display the measured time from session acquire to key delivery (e.g. `312ms`). Latency may be inflated on key rotation events.
- **Summary row**: the overlay header shows a live DRM line such as `Widevine  ·  Keys Loaded  ·  312ms`; cleared when the session is released.

The DRM event list is capped at **200 entries**. DRM session manager errors are also forwarded to the **Errors** tab as `DRM` entries so all errors remain visible in one place.

### 5. Errors

A dedicated view for silent, non-fatal errors that ExoPlayer absorbs without triggering a fatal `PlaybackException`. Activated via the **`⚠ N`** pill indicator in the overlay header.

- **`⚠ N` header indicator**: appears as soon as the first error is captured, with a count of total errors. Tap to open the Errors view from any active tab.
- **Six captured categories**:
  - `LOAD` (red) — segment or manifest HTTP errors (`onLoadError` for `DATA_TYPE_MEDIA` / `DATA_TYPE_MANIFEST`).
  - `CODEC` (orange) — video codec failures (`onVideoCodecError`).
  - `FRAMES` (yellow) — dropped video frame bursts ≥ 3 frames (`onDroppedVideoFrames`).
  - `AUDIO` (purple) — audio sink errors (`onAudioSinkError`).
  - `ACODEC` (green) — audio codec failures (`onAudioCodecError`).
  - `DRM` (cyan) — DRM session manager errors (`onDrmSessionManagerError`); also surfaced in the DRM tab.
- **Back / Clear / Share**: the errors view header has a ← Back button to restore the previous tab, a Clear button to empty the list, and a ↗ Share button that fires an `ACTION_SEND` intent with the full error list as plain text.
- **Inline expand**: each row shows a `▾`/`▴` chevron to signal tap-to-expand; tapping reveals the full URI, exception text, and absolute timestamp.
- **Dropped-frames dedup**: consecutive `DROPPED_FRAMES` events within a 5-second window are merged into a single entry — the message updates to `"X frames dropped (N bursts)"` so slow devices don't flood the list.
- The error list is capped at **200 entries**; oldest entries are dropped when the cap is reached (except when the newest event merges into the last entry).

---

## Roadmap

Coarse milestones. Each will be broken down into a TODO checklist as work begins.

- **M0 — Scaffolding** ✅: Gradle module, `implementation` distribution with host-managed gating, empty `attach` / `detach` API surface.
- **M1 — HLS MVP** ✅: Master playlist parsing, variant/rendition listing, basic overlay with active track display.
- **M2 — Segment & CDN** ✅: Per-segment timing (total duration, size, throughput) and CDN response header capture with cache hit/miss flagging.
- **M3 — ABR Log** ✅: Track switch event recording with buffer state, switch reason, and chronological timeline view in the overlay.
- **M4 — DASH Support** ✅: DASH track enumeration via the Media3 `Tracks` API, feature parity with HLS across all prior milestones.
- **M5 — Distribution** ✅: Published to Maven Central (`io.github.oguzhaneksi:streamprobe:0.3.2`).
- **M6 — Background Error Tracking** ✅: Exposing silent, non-fatal background errors — segment load failures (HTTP 404/5xx), video codec errors (`onVideoCodecError`), audio codec errors (`onAudioCodecError`), dropped frame bursts (`onDroppedVideoFrames`), and audio sink errors (`onAudioSinkError`) — as a real-time Errors view in the overlay, reachable via a header `⚠ N` indicator.
- **M7 — Audio & Subtitle Tracks** ✅: Audio/subtitle rendition enumeration (HLS muxed sources included) + active audio/subtitle overlay; ABR switch events expanded to sealed `TrackSwitchEvent` covering video, audio and subtitle switches.
- **M8 — DRM Monitoring** ✅: DRM session lifecycle tracking (Widevine, PlayReady, ClearKey) via a dedicated `DrmSessionTracker` analytics listener. Captures session acquire/release events, license key load latency, and DRM manager errors. A live **DRM** summary row appears in the overlay header; a **DRM** chip reveals a chronological DRM event timeline. DRM errors are dual-surfaced in both the DRM tab and the Errors tab.
- **M9 — TTFB & Advanced Network Metrics (baseline)** ✅: Best-effort TTFB capture via a `DataSource.Factory` wrapper (`StreamProbe.wrapDataSourceFactory`). The `open()`-duration proxy approximates TTFB and is correlated to each media segment by request URI + byte position; shown in the Segments tab as `~NNms`. Per-phase DNS/connect/TLS breakdown and `NetworkInspector`/OkHttp/Cronet/HttpEngine adapters are deferred.

- **Kotlin Multiplatform + iOS** ✅: Migrated the SDK to Kotlin Multiplatform — the pure core (`SessionStore`, models, `OverlayPresenter`, formatters, parsers, registries) moved to `commonMain` and is shared verbatim with Android. Added iOS targets and a native Swift `AVPlayer` adapter that renders the full draggable overlay in UIKit (orientation-aware layout, drag, expandable rows, auto-scroll, share — at parity with Android). iOS ships as a two-layer Swift Package: a Kotlin `StreamProbeCore` binary XCFramework + a Swift `StreamProbe` layer, bridged by SKIE, distributed via SPM (versioned independently of Android, starting at `0.1.0`). Android's published public API and Maven Central flow are unchanged.
- **M10 — SSAI & Timeline Metadata** *(Planned)*: Listening to `onMetadata` for SCTE-35 and ID3 tags to visually distinguish Server-Side Ad Insertion (SSAI) ad breaks from main content.
- **M11 — Advanced Network Metrics (per-phase)** *(Planned)*: True per-phase DNS/connect/TLS breakdown via a `NetworkInspector` abstraction supporting OkHttp, Cronet, and HttpEngine adapters.
- **M12 — QoS Stall Diagnosis** *(Planned)*: Treat playback stalls as a **QoS** signal, not a QoE one — when playback rebuffers, classify the *root cause* from the delivery layer rather than just recording that a stall happened. Each stall is diagnosed as `SEGMENT_LOAD_FAILURE` (a segment/manifest fetch failed just before the stall), `BANDWIDTH_STARVATION` (measured throughput below the selected rendition bitrate), `THROUGHPUT_HEALTHY` (network fine → cause is upstream: manifest discontinuity / buffer policy), or `UNKNOWN`, and surfaced in the **Errors** view as a `STALL` entry carrying the verdict plus the throughput-vs-selected evidence. Stall detection is wired on both the Android (`STATE_BUFFERING` after `STATE_READY`, seek-suppressed) and iOS (`AVPlayerItemPlaybackStalledNotification`) players, sharing a single cross-platform classifier. Also signals segment-cardinality honestly: iOS roll-up access-log metrics are badged `AGG` to distinguish them from Android's true per-segment data.

---

## Known Limitations

### General

- **Multi-period DASH:** ExoPlayer merges tracks from all Periods into a single `Tracks` object. If the same representation appears in multiple Periods (e.g., around ad boundaries), it may be listed more than once. Period-aware grouping is a planned future enhancement.
- **Audio-Only Streams:** StreamProbe currently focuses heavily on video variant and segment analysis. Audio-only HLS/DASH streams are not officially supported yet and may yield incomplete track or ABR logs.

### iOS

The Android implementation hooks Media3 at the analytics/HTTP layer; the iOS implementation observes AVFoundation, which exposes a coarser surface. As a result some features differ on iOS:

- **Segment metrics are aggregate, not per-segment.** AVFoundation does not expose individual segment downloads. iOS derives segment metrics from `AVPlayerItem` access-log events, which are rolling roll-ups over the session. These are badged **`AGG`** in the overlay to distinguish them from Android's true per-segment data.
- **No per-segment CDN headers or TTFB.** `AVPlayer` does not surface HTTP response headers or per-request `open()` timing, so the CDN cache hit/miss indicator and the TTFB estimate (`~NNms`) are **Android-only**.
- **DRM monitoring is Android-only.** The DRM lifecycle timeline (Widevine / PlayReady / ClearKey) relies on Media3's `DrmSessionManager` callbacks. iOS uses **FairPlay**, which has no equivalent observation surface today — see below.
- **No item-change tracking.** The iOS probe scopes its observers to the player's `currentItem`. `AVQueuePlayer` item transitions (advancing to the next queued item) are not yet tracked.
- **Simulator TLS.** Live HTTPS playback fails inside the sandboxed iOS Simulator (its network daemon can't complete TLS handshakes). This is a Simulator limitation, not an SDK defect — verify live behaviour on a real device.

### FairPlay DRM (iOS)

FairPlay DRM monitoring on iOS is **not yet supported** and is planned for evaluation in a **future phase**. It is gated on external FairPlay license/key-server infrastructure, which is required to meaningfully observe and verify the session lifecycle. Until then, DRM diagnostics are available on Android only.

---

## R8 / ProGuard

StreamProbe ships with an empty `consumer-rules.pro`. No additional keep rules are required in your own ProGuard/R8 configuration — Media3 and AndroidX ship their own consumer rules, and StreamProbe's public API surface relies only on those libraries and standard Android APIs.

If you are using R8 in full-mode and notice issues, please open an issue with the relevant diagnostic output.

---

## Demo Apps

The repository includes a demo application for each platform that shows a complete, real-world integration.

**Android (`app/` module):**
- Streams are selected from a pre-populated list (HLS and DASH sources).
- The `StreamProbe` instance lives in a `ViewModel`, with `attach()` called after the player is built and `show()` called from `Activity.onCreate()`.
- `detach()` is called in `ViewModel.onCleared()`, ensuring the session is torn down when the player is released.

Clone the repository and open it in Android Studio to run the demo directly.

**iOS (`iosApp/` — SwiftUI):**
- An HLS stream catalog with a fullscreen `AVPlayer` screen and a settings screen (overlay toggle, auto-play, loop).
- Consumes the `StreamProbe` Swift package as a local SPM dependency and installs the overlay window via `makeOverlayWindow(windowScene:)`.
- The Xcode project is generated by [XcodeGen](https://github.com/yonaskolb/XcodeGen). Run `./gradlew :sdk:assembleStreamProbeCoreDebugXCFramework` and regenerate the project with `iosApp/generate.sh` before opening in Xcode.

---

## Contributing

We welcome contributions from the community! If you've found a bug, want to add a feature, or improve documentation, follow these standard open-source steps:

1. **Fork** the repository and clone it locally.
2. **Create a branch** for your feature or bugfix (`git checkout -b feature/awesome-new-tool`).
3. **Commit** your changes with clear, descriptive messages.
4. **Push** your branch to your fork (`git push origin feature/awesome-new-tool`).
5. **Open a Pull Request** against the `main` branch. 

Please ensure any changes pass existing tests or add new ones where applicable.

---

## Spec

For the detailed feature breakdown, API surface, and technical design, see [`SPEC.md`](./SPEC.md).

---

## License

Apache License 2.0. See [`LICENSE`](./LICENSE) for details.
