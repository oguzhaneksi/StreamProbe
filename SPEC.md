# StreamProbe — Specification

This document describes the goals, scope, features, and technical design of StreamProbe. For a short overview, see [`README.md`](./README.md).

---

## 1. Problem Statement

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

### 3.1 Manifest Parsing

When a master playlist (HLS) or MPD (DASH) is loaded, StreamProbe reads the manifest from `ExoPlayer.currentManifest` after the player has loaded it. The content is parsed into a structured object containing:

- All variant streams and renditions
- Codec, bitrate, and resolution per variant

> **Timing consideration:** `getCurrentManifest()` returns `null` until the player has fetched and parsed the manifest. All consumers (including the overlay) must handle the `null` case — typically by rendering a loading/placeholder state until the manifest is available.

### 3.2 Variant and Rendition Listing

Every available video, audio, and subtitle track is enumerated. For each track, the overlay displays:

- Bandwidth
- Resolution
- Codec

The track currently selected by the player is flagged in real time, so the developer can see at a glance which variant is actually being played.

### 3.3 Segment Download Metrics

Each segment request is recorded with:

- Request timestamp
- ~~TTFB (time to first byte)~~ — Deferred. Requires `MediaSource.Factory` wrapper or `TransferListener` integration. Planned for a future milestone.
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

### 3.5 ABR Switch Log

Every time the player changes quality, a record is created containing:

- Previous track
- New track
- Timestamp
- Buffer state at the time of the switch
- The reason ExoPlayer reports for the decision

These records are kept in chronological order and rendered as a timeline in the overlay.

### 3.6 Debug Overlay

A draggable panel rendered on top of the host app via `Activity.addContentView()`. This approach keeps the overlay **entirely within the host app's own window**, so **no extra permissions** (e.g., `SYSTEM_ALERT_WINDOW` / `ACTION_MANAGE_OVERLAY_PERMISSION`) are required.

At a glance it shows:

- The currently active rendition (or a loading state if the manifest is not yet available)
- The most recent segment metrics
- Current CDN cache hit/miss state

A **filter chip row** inside the panel lets the developer switch between three views:

- **Variants** (default) — per-variant resolution, bitrate, and codec with an active-track indicator.
- **Segments** — per-segment download duration, size, throughput, and cache-status dot. The segment timeline is shown in the same overlay panel with no Activity transition, so the host player is never backgrounded.
- **ABR** — chronological list of every quality switch, showing the from→to resolution (or bitrate when resolution is identical), buffer duration at the time of the switch, switch reason (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`), and a relative timestamp from the first event.

A fourth view, **Errors**, is reachable via a `⚠ N` header indicator pill (hidden when no errors are present) and is described in §3.8.

#### Orientation-aware layout

- **Portrait** — 310 dp wide vertical stack: stats (Active Track, Latest Segment, CDN Status) → chip row → list.
- **Landscape** — 440 dp wide horizontal split: left column for stats, right column for chip row + list. The list height is capped at `screenHeight × 0.55`, clamped to `[200 dp, 360 dp]`.

For host apps that declare `android:configChanges` covering orientation, the panel rebuilds in place via `View.onConfigurationChanged`. For apps that don't, the Activity recreates and the new Activity's `show(this)` call re-adds the panel. The selected chip (`viewMode`) is preserved across both rebuild paths — including when the user is in the Errors view.

### 3.8 Background Error Tracking

Silent, non-fatal errors that ExoPlayer absorbs are captured and surfaced in a dedicated Errors view reachable from the overlay header. Four categories are tracked:

| Category | Trigger | Colour |
|---|---|---|
| `LOAD_ERROR` | `onLoadError` for `DATA_TYPE_MEDIA` or `DATA_TYPE_MANIFEST`; `wasCanceled=true` is filtered out; `DATA_TYPE_DRM` is deferred to M8 | Red |
| `VIDEO_CODEC_ERROR` | `onVideoCodecError` | Orange |
| `DROPPED_FRAMES` | `onDroppedVideoFrames` with ≥ 3 frames dropped | Yellow |
| `AUDIO_SINK_ERROR` | `onAudioSinkError` | Purple |

**Header indicator** — a `⚠ N` pill appears in the overlay header (between the title and the collapse arrow) as soon as the first error is captured. Tapping it switches the body to the Errors view; if the body is collapsed, it auto-expands first.

**Errors view** — replaces the chip row with a back/title/clear/share header and renders a chronological error timeline. Each row shows index, category dot, category label, one-line message, and relative timestamp. Tapping a row expands an inline detail container showing the full message, exception text, and absolute wall-clock timestamp.

**Dropped-frames dedup** — consecutive `DROPPED_FRAMES` events within a 5-second window are merged into a single entry. The merged entry updates its message to `"X frames dropped (N bursts)"`. The original `timestampMs` is preserved (stable DiffUtil identity; no flicker).

**Error cap** — 200 entries, matching `MAX_ABR_EVENTS`. Oldest entries are dropped at the cap boundary, except when the newest event merges into the existing last entry.

**Share export** — the Share button fires `Intent.ACTION_SEND` with a plain-text listing of all captured errors, formatted as `[StreamProbe] N errors` followed by one row per error.

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

## 4. Technical Design

### 4.1 Interception Points

StreamProbe instruments a single layer via a standard Media3 `AnalyticsListener` wired to the player:

- **`onTimelineChanged`** — reads `ExoPlayer.currentManifest` to extract manifest info (HLS multivariant playlist or DASH MPD) into SDK-owned models.
- **`onLoadCompleted`** — captures per-segment download duration, byte count, computed throughput, and HTTP response headers (CDN cache hit/miss). Works regardless of the underlying HTTP stack.
- **`onDownstreamFormatChanged`** / **`onVideoInputFormatChanged`** — detects ABR switches and records the previous/new track, buffer state, and Media3 selection reason.
- **`onLoadError`** — captures segment and manifest HTTP errors; cancellations (`wasCanceled=true`) and DRM data types are filtered out.
- **`onVideoCodecError`** — captures hardware/software codec failures.
- **`onDroppedVideoFrames`** — captures dropped-frame bursts ≥ 3 frames with dedup logic.
- **`onAudioSinkError`** — captures audio sink failures.

All callbacks feed a single thread-safe in-memory `SessionStore`, which the overlay reads from via `StateFlow`.

**Planned (future milestone):** A `MediaSource.Factory` wrapper and a `NetworkInspector` abstraction (OkHttp, Cronet, and HttpEngine adapters) to enable true TTFB capture and richer per-request timing beyond what `onLoadCompleted` exposes.

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

### 4.3 Target Platform

- **OS**: Android
- **Language**: Kotlin
- **Player**: Media3 ExoPlayer
- **HTTP stack**: OkHttp (built-in adapter), Cronet and HttpEngine (planned adapters)
- **Overlay rendering**: `Activity.addContentView()` — no `SYSTEM_ALERT_WINDOW` permission required

### 4.4 Distribution

- **Current**: Maven Central — `io.github.oguzhaneksi:streamprobe:0.1.0`.
- **Future**: A no-op stub artifact (`streamprobe-noop`) to simplify release-build exclusion without `BuildConfig` guards.

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