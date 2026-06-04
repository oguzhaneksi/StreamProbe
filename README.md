# StreamProbe

A debug SDK for Android apps that inspects HLS and DASH streaming traffic in real time on top of Media3/ExoPlayer, surfacing manifest contents, segment metrics, CDN headers, and ABR decisions through an in-app overlay.

https://github.com/user-attachments/assets/471e76d8-6653-4116-ae1d-0e1f3f023234

> ⚠️ **Development-only tool.** StreamProbe is designed for debug builds and should be omitted from release builds by gating it behind `BuildConfig.DEBUG` or a similar host-managed mechanism. It is not an analytics, QoE, or production monitoring product.

---

## Requirements

Before you begin, ensure your project meets the following requirements:
- **Minimum SDK**: Android 6.0 (API level 23) or higher.
- **Media3/ExoPlayer**: Version `1.10.0` or higher.
- **Kotlin**: Version `1.9.0`+ (currently built against `2.3.20`).

---

## Why This Exists

When something goes wrong with stream delivery in an Android app — the wrong rendition gets picked, an unexpected ABR switch happens, segment latency spikes, or a CDN cache miss slips through — the developer's options today are all awkward:

- **Charles Proxy**: wire the device through a proxy, install an SSL cert, filter traffic by hand.
- **`adb logcat`**: sift through raw ExoPlayer logs looking for the relevant lines.
- **Manual manifest inspection**: copy the URL into a browser and read the `.m3u8` / MPD by eye.
- Usually, all three at once.

Each round of this takes 15–30 minutes, depends on a tethered device, requires external tools, and can't be handed off to a teammate. StreamProbe pulls the whole workflow into the app itself: the moment the SDK is attached, everything is captured automatically and readable through an on-screen overlay.

ExoPlayer's built-in `EventLogger` and `DebugTextViewHelper` only surface player events — they don't touch manifest contents, CDN headers, or segment timing. StreamProbe closes that gap.

---

## High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                      Android App (debug)                    │
│                                                             │
│   ┌──────────────────┐          ┌────────────────────┐      │
│   │  Media3 /        │          │  Debug Overlay UI  │      │
│   │  ExoPlayer       │◄─────────┤  (draggable panel) │      │
│   └────────┬─────────┘          └──────────▲─────────┘      │
│            │                               │                │
│            │ attach(player)                │ reads          │
│            ▼                               │                │
│   ┌─────────────────────────────────────┐  │                │
│   │         StreamProbe Core            │──┘                │
│   │                                     │                   │
│   │  ┌──────────────────────────────┐   │                   │
│   │  │  AnalyticsListener (Media3)  │   │                   │
│   │  │  onTracksChanged             │   │                   │
│   │  │  onLoadCompleted             │   │                   │
│   │  │  onVideoInputFormatChanged   │   │                   │
│   │  │  onDownstreamFormatChanged   │   │                   │
│   │  └──────────────┬───────────────┘   │                   │
│   └─────────────────┼─────────────────  ┘                   │
│                     │                                       │
│                     ▼                                       │
│       track snapshot · segment metrics · switch events      │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
                   HLS / DASH origin
                        + CDN
```

A single `AnalyticsListener` wired to the player feeds an in-memory session store that the overlay reads from:

- **Track snapshot** — built from `player.currentTracks` on `onTracksChanged`; all video variants, audio renditions, and subtitle tracks are enumerated into a protocol-agnostic `TracksSnapshot` (works identically for HLS and DASH). Each track carries an `isSelected` flag that drives the active-dot indicator in the overlay without secondary comparison.
- **Segment metrics and CDN headers** — captured on `onLoadCompleted`; per-segment download duration, size, throughput, and HTTP response headers (including cache hit/miss status) are stored for the session.
- **Track switch events** — `VideoSwitch` events are emitted from `onVideoInputFormatChanged` (the decoder-level format change is the authoritative signal); audio and subtitle switch events are emitted from `onDownstreamFormatChanged`. Every video quality change, audio rendition switch, and subtitle selection (including disable) is recorded as a `TrackSwitchEvent` (`VideoSwitch` / `AudioSwitch` / `SubtitleSwitch`) with the previous/new track, buffer state at switch time, and the reason Media3 reports (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`). Events are kept in a capped chronological list and displayed in the overlay's Switches tab.

StreamProbe is distributed as a standard `implementation` dependency. Host apps guard the `attach()` calls behind `BuildConfig.DEBUG` to ensure zero runtime overhead in release builds.

---

## Installation

StreamProbe is distributed via Maven Central. Add the dependency to your app-level `build.gradle.kts` file:

```kotlin
dependencies {
    // Add the core StreamProbe SDK
    implementation("io.github.oguzhaneksi:streamprobe:<version_name>")
}
```

*Note: Even though it is an `implementation` dependency, we recommend gating the SDK activation behind debug checks in the usage step to ensure it remains a development-only tool without compilation errors in release builds.*

---

## Usage

Integrating StreamProbe into your existing Media3/ExoPlayer setup requires only a few lines of code.

### 1. Initialize and Attach to the Player

Create an instance of `StreamProbe` and attach it right after building your player:

```kotlin
val streamProbe = StreamProbe()

// Inside your ViewModel, PlayerManager, or Activity where the player is created:
val player = ExoPlayer.Builder(context).build()

if (BuildConfig.DEBUG) {
    streamProbe.attach(player)
}
```

### 2. Show the Overlay in your Activity

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

### 3. Cleanup

When your player is released, don't forget to detach the probe to avoid memory leaks:

```kotlin
if (BuildConfig.DEBUG) {
    streamProbe.detach()
}
player.release()
```

---

## Debug Overlay Details

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
- **M9 — TTFB & Advanced Network Metrics** *(Planned)*: True time-to-first-byte capture via a `MediaSource.Factory` wrapper and a `NetworkInspector` abstraction supporting OkHttp, Cronet, and HttpEngine adapters.
- **M10 — SSAI & Timeline Metadata** *(Planned)*: Listening to `onMetadata` for SCTE-35 and ID3 tags to visually distinguish Server-Side Ad Insertion (SSAI) ad breaks from main content.

---

## Known Limitations

- **Multi-period DASH:** ExoPlayer merges tracks from all Periods into a single `Tracks` object. If the same representation appears in multiple Periods (e.g., around ad boundaries), it may be listed more than once. Period-aware grouping is a planned future enhancement.
- **Audio-Only Streams:** StreamProbe currently focuses heavily on video variant and segment analysis. Audio-only HLS/DASH streams are not officially supported yet and may yield incomplete track or ABR logs.

---

## R8 / ProGuard

StreamProbe ships with an empty `consumer-rules.pro`. No additional keep rules are required in your own ProGuard/R8 configuration — Media3 and AndroidX ship their own consumer rules, and StreamProbe's public API surface relies only on those libraries and standard Android APIs.

If you are using R8 in full-mode and notice issues, please open an issue with the relevant diagnostic output.

---

## Demo App

The repository includes a demo application (`app/` module) that shows a complete, real-world integration:
- Streams are selected from a pre-populated list (HLS and DASH sources).
- The `StreamProbe` instance lives in a `ViewModel`, with `attach()` called after the player is built and `show()` called from `Activity.onCreate()`.
- `detach()` is called in `ViewModel.onCleared()`, ensuring the session is torn down when the player is released.

Clone the repository and open it in Android Studio to run the demo directly.

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
