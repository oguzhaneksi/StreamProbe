# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamProbe is an Android debug SDK that hooks into Media3/ExoPlayer to surface HLS/DASH streaming diagnostics (track variants, segment metrics, CDN headers, ABR decisions, playback errors) through an in-app draggable overlay. It is **debug-only** — never enabled in release builds.

Two Gradle modules:
- **`sdk/`** — the published library (`io.github.oguzhaneksi:streamprobe`)
- **`app/`** — demo app that exercises the SDK against real HLS/DASH streams

## Build & Development Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run all unit tests
./gradlew test

# Run SDK unit tests only
./gradlew :sdk:test

# Run a single test class
./gradlew :sdk:test --tests "com.streamprobe.sdk.internal.PlayerInterceptorTest"

# Android Lint
./gradlew lint

# Ktlint check
./gradlew ktlintCheck

# Ktlint auto-fix
./gradlew ktlintFormat

# Detekt static analysis
./gradlew detekt
```

CI runs all four checks (`test`, `lint`, `ktlintCheck`, `detekt`) on every PR.

## Code & Formatting Standards

All code must pass `ktlintCheck`, `detekt`, and `lint` before merging. `detekt` is configured with `maxIssues: 0` — zero tolerance for new issues.

### Ktlint
- Enforces the official Kotlin coding style (version 14.2.0).
- Run `./gradlew ktlintFormat` to auto-fix most formatting issues before committing.
- Wildcard imports (`import foo.*`) are forbidden.

### Detekt (`config/detekt/detekt.yml`)
Notable active rules and their thresholds:

| Rule | Threshold |
|------|-----------|
| `LongMethod` | 60 lines |
| `LongParameterList` | 8 (functions), 8 (constructors) |
| `TooManyFunctions` | 20 per class/file, 15 per interface/enum |
| `MaxLineLength` | 140 characters |
| `WildcardImport` | Active (forbidden) |
| `MagicNumber` | Disabled |

- Composable and test functions are exempt from `FunctionNaming` (uppercase names allowed).
- Do not suppress detekt warnings with `@Suppress` or `// detekt:disable` — fix the root cause.

### Media3 `@UnstableApi`
All classes and functions that reference Media3 `@UnstableApi` types must be annotated with `@androidx.annotation.OptIn(UnstableApi::class)` (public API) or `@UnstableApi` (internal). The public `StreamProbe` class uses `@androidx.annotation.OptIn`; internal classes use `@UnstableApi` directly.

### General conventions
- `internal` visibility for everything not part of the public SDK surface.
- `data class` for all model types; `sealed interface` for type hierarchies (`TrackSwitchEvent`, `TrackListInfo`, `RenditionListItem`, `ErrorDetail`).
- No XML layouts in the SDK — all views in `overlay/` are constructed programmatically to avoid requiring R resources in a library module.
- `String.format(Locale.ROOT, ...)` must be used for all locale-sensitive formatting (enforced by lint).

## Architecture

### Data flow

```
ExoPlayer
   │
   ├─ Player.Listener
   └─ AnalyticsListener
          │
   PlayerInterceptor          (maps Media3 events → SDK models)
          │
    SessionStore              (StateFlow-backed in-memory store)
          │
   OverlayManager             (collects flows on Main dispatcher)
          │
   OverlayPanelView           (programmatic View hierarchy, added via addContentView)
```

### Module: `sdk/`

#### Public surface

| File | Role |
|------|------|
| `StreamProbe.kt` | Public entry point. Owns `SessionStore`, `NetworkTimingRegistry`, `PlayerInterceptor`, and `OverlayManager`. Player lifecycle (`attach`/`detach`) is **independent** of Activity lifecycle (`show`/`hide`) — the `StreamProbe` instance typically lives in a `ViewModel`. `wrapDataSourceFactory(factory)` is the public entry point for TTFB measurement; it wraps the given factory in a `TimingDataSourceFactory` and must be the **outermost** wrapper. |

#### Internal: interception layer

| File | Role |
|------|------|
| `internal/PlayerInterceptor.kt` | Implements both `Player.Listener` and `AnalyticsListener`. Captures track selections, segment load completions, and playback errors; maps Media3 `Format` to SDK models via `FormatExtensions`; writes to `SessionStore`. Also registers/unregisters `DrmSessionTracker` on `attach`/`detach`. In `onLoadCompleted`, consumes a `NetworkTimingRegistry` entry keyed on **`loadEventInfo.dataSpec.uri`** (not `loadEventInfo.uri` — the redirect-key invariant, see below) to fold TTFB into `SegmentMetric.networkTiming`. |
| `internal/TimingDataSourceFactory.kt` | `@UnstableApi` `DataSource.Factory` wrapper. Times `open()`-duration as a TTFB proxy for HTTP/HTTPS schemes only; records in `NetworkTimingRegistry` keyed by request URI + byte position. If `open()` throws, no entry is written. Uses `SystemClock.elapsedRealtimeNanos()` to stay in the same clock domain as `loadDurationMs`. |
| `internal/NetworkTimingRegistry.kt` | Bounded, thread-safe handoff between the I/O-thread writer (`TimingDataSource.open`) and the playback-thread reader (`PlayerInterceptor.onLoadCompleted`). Keys entries as `"uri|position"` (pipe delimiter to prevent collisions with URIs containing `@`). FIFO eviction via `LinkedHashMap.removeEldestEntry` at `MAX_ENTRIES = 128`. |
| `internal/DrmSessionTracker.kt` | Separate `AnalyticsListener` handling DRM callbacks only (`onDrmSessionAcquired`, `onDrmKeysLoaded`, `onDrmSessionReleased`, `onDrmSessionManagerError`). Keeps `PlayerInterceptor`'s function count within detekt's `TooManyFunctions` limit. Measures license latency as wall-clock delta from acquire to keys-loaded. DRM errors are dual-surfaced to both `drmSessionEvents` and `playbackErrors`. |
| `internal/DrmSchemeDetector.kt` | Pure `internal object` with no Android framework dependency. Resolves `DrmScheme` from `eventTime.timeline` (not `player.currentMediaItem` — correct during playlist transitions) and maps `DrmSession.State` integers to `DrmSessionState`. |
| `internal/FormatExtensions.kt` | Extension functions mapping `Media3.Format` to SDK model types. `toAudioTrackInfoDetecting` infers `isMuxed` from container MIME type; `toSubtitleTrackInfoDetecting` infers `SubtitleKind` (CC vs SIDECAR) from sample MIME type. |
| `internal/CdnHeaderParser.kt` | Parses HTTP response headers (case-insensitively) into `CdnHeaderInfo`. Detects CDN provider (Cloudflare, CloudFront, Fastly, Akamai) and cache status (HIT/MISS/STALE/BYPASS/UNKNOWN). |

#### Internal: state store

| File | Role |
|------|------|
| `internal/SessionStore.kt` | Thread-safe `StateFlow` store. Capped lists: 500 segment metrics, 200 switch events, 200 errors, 200 DRM events. Consecutive `DROPPED_FRAMES` events within a 5 s window are merged into one aggregated entry (stable `timestampMs` for DiffUtil). Exposes `drmSessionEvents: StateFlow<List<DrmSessionEvent>>` and `currentDrmState: StateFlow<DrmStatusInfo?>`. |

#### Internal: overlay

| File | Role |
|------|------|
| `internal/overlay/OverlayManager.kt` | Manages overlay lifecycle tied to `ComponentActivity`. Adds/removes `OverlayPanelView` via `addContentView`. Registers a `DefaultLifecycleObserver` that auto-calls `hide()` on `onDestroy`, preventing leaks. Persists `ViewMode` (TRACKS / SEGMENTS / SWITCHES / DRM / ERRORS) across orientation rebuilds. Collects `SessionStore` flows in a `CoroutineScope(SupervisorJob() + Dispatchers.Main)` that is cancelled on `hide()`. `observeDrm()` shows/hides the DRM chip, summary row, and DRM tab reactively. Landscape panel width is 540 dp (up from 440 dp). |
| `internal/overlay/OverlayPanelView.kt` | Fully programmatic `LinearLayout` — no XML inflation, no `R` resource references. Constructs the entire header + body hierarchy in `init`. Supports portrait (vertical stack) and landscape (left stats column / right list column) layouts. Exposes `drmChip`, `drmSectionLabel`, and `drmStatusView` fields. Contains `BoundedRecyclerView`, an inner `RecyclerView` subclass that caps its measured height at a computed `maxHeightPx`. Forwards orientation changes to `OverlayManager` via `onOrientationChanged` callback. |
| `internal/overlay/RenditionListAdapter.kt` | `ListAdapter` for the Tracks tab. Item type is the `RenditionListItem` sealed interface (`SectionHeader`, `Video`, `Audio`, `Subtitle`). DiffUtil identity uses `Format.id` when available, falls back to dimensions + bitrate for video and `isSameRenditionAs` for audio/subtitle. |
| `internal/overlay/SegmentTimelineAdapter.kt` / `SwitchTimelineAdapter.kt` / `ErrorTimelineAdapter.kt` / `DrmTimelineAdapter.kt` | `ListAdapter` implementations for the Segments, Switches, Errors, and DRM tabs respectively. All auto-scroll to the newest item unless the user has scrolled up. `DrmTimelineAdapter` uses `DrmSessionEvent.id` as the DiffUtil identity key. |
| `internal/overlay/DrmTimelineItemView.kt` | Single programmatic row for the DRM tab. Layout: index → colour-coded event dot → scheme badge → event label → latency (visible only for `KeysLoaded`) → relative timestamp. |
| `internal/overlay/DrmFormatters.kt` | Pure formatting functions for DRM data (no Android framework dependency). `formatDrmStatus` produces the summary-panel string; `formatDrmSchemeBadge` returns the compact chip label (`WV`/`PR`/`CK`/`DRM`). |
| `internal/overlay/OverlayFormatters.kt` | Pure formatting functions (no Android framework dependency except `Locale`). Extracted from `OverlayManager` so they can be unit-tested without Robolectric. Includes `formatErrorsForExport` for the share-errors intent. Uses a `ThreadLocal<SimpleDateFormat>` for `HH:mm:ss.SSS` formatting (API 23 compatibility; `ThreadLocal.withInitial` requires API 26). |

#### Models (`model/`)

| Type | Description |
|------|-------------|
| `TrackListInfo` | Sealed interface — protocol-agnostic track snapshot. Implemented only by `TracksSnapshot`. |
| `TracksSnapshot` | Concrete `data class` populated from `player.currentTracks`. |
| `VariantInfo` | Single video rendition. `isSelected` set from `Tracks.Group.isTrackSelected(i)`. Nullable `id` from `Format.id` is preferred over dimension matching in DiffUtil. |
| `AudioTrackInfo` | Audio rendition. `isMuxed = true` when audio is muxed into the video container. |
| `SubtitleTrackInfo` | Subtitle/CC rendition. `kind` distinguishes `SIDECAR` (WebVTT/TTML) from `CC` (CEA-608/CEA-708). |
| `ActiveTrackInfo` | Currently decoded video track (from `onVideoInputFormatChanged`). Distinct from `VariantInfo` — sourced from the decoder, not the track group. |
| `SegmentMetric` | Per-segment timing, size, throughput, `CdnHeaderInfo`, and optional `NetworkTiming`. |
| `NetworkTiming` | Best-effort TTFB breakdown: `ttfbMs`, `transferDurationMs?`, reserved per-phase fields (`dnsMs?`, `connectMs?`, `tlsMs?`), and `isEstimated` flag. No Media3 types; `@UnstableApi`-free. |
| `TrackSwitchEvent` | Sealed interface with `VideoSwitch`, `AudioSwitch`, `SubtitleSwitch`. `SubtitleSwitch.newTrack` is nullable (null = subtitle disabled). |
| `PlaybackErrorEvent` | Immutable error record. `categoryDetail: ErrorDetail?` carries structured data (e.g. `DroppedFrames` burst aggregation, `DrmErrorInfo` for DRM failures). `timestampMs` is stable across merges for DiffUtil. |
| `ErrorCategory` | `LOAD_ERROR`, `VIDEO_CODEC_ERROR`, `DROPPED_FRAMES`, `AUDIO_SINK_ERROR`, `AUDIO_CODEC_ERROR`, `DRM_ERROR`. |
| `DrmScheme` | `WIDEVINE`, `PLAYREADY`, `CLEARKEY`, `UNKNOWN`. |
| `DrmSessionState` | `OPENING`, `OPENED`, `OPENED_WITH_KEYS`, `RELEASED`, `ERROR`, `UNKNOWN`. |
| `DrmSessionEvent` | Sealed interface with four subtypes: `SessionAcquired` (scheme + initial state), `KeysLoaded` (scheme + `licenseLatencyMs`), `SessionReleased` (scheme), `SessionError` (scheme + message + detail). Each subtype carries a monotonically increasing `id` used as a stable DiffUtil key. |
| `DrmStatusInfo` | Live DRM summary: `scheme`, `state`, `lastLicenseLatencyMs?`. Written to `SessionStore.currentDrmState` on every DRM event; drives the overlay summary row. |

### Key design decisions

- **Video switch signal split:** `onVideoInputFormatChanged` (decoder-level) is the authoritative source for `VideoSwitch` events. `onDownstreamFormatChanged` only caches the selection reason into `pendingVideoSwitchReason`, which is consumed when the format change fires. This avoids duplicate events.
- **`DEFAULT` track type guard:** In `onDownstreamFormatChanged`, a `DEFAULT`-type event only updates `pendingVideoSwitchReason` when `format.width > 0 || format.height > 0`, preventing a muxed-audio `DEFAULT` event from overwriting the pending video reason.
- **Subtitle-disabled event:** When `probeTracks` finds `foundSubtitle == null` but `lastSubtitleTrack != null`, it emits a `SubtitleSwitch(newTrack = null)` with `SwitchReason.MANUAL` to record the user turning off subtitles.
- **`isSelected` flag:** Set directly from `Tracks.Group.isTrackSelected(i)` into model objects. Neither `OverlayManager` nor adapters perform secondary active-track comparisons.
- **No XML in SDK:** `OverlayPanelView` and all item views are built programmatically so the SDK has no dependency on `res/` or `R` classes from the host app.
- **DRM tracker isolation:** DRM callbacks are handled by a separate `DrmSessionTracker` `AnalyticsListener` rather than inline in `PlayerInterceptor`. This keeps `PlayerInterceptor`'s method count within detekt's `TooManyFunctions` limit (20 per class) while keeping DRM logic cohesive.
- **DRM scheme from timeline:** `DrmSchemeDetector.detectScheme` reads from `eventTime.timeline` rather than `player.currentMediaItem` to avoid a race during playlist transitions where `currentMediaItem` may already point to the next item.
- **DRM dual surface:** `onDrmSessionManagerError` writes to both `drmSessionEvents` (DRM tab) and `playbackErrors` (Errors tab). There is a single write path — no duplication in the store or UI.
- **TTFB redirect-key invariant:** In `PlayerInterceptor.onLoadCompleted`, the `NetworkTimingRegistry` lookup **must** use `loadEventInfo.dataSpec.uri` (the pre-redirect request URI that `TimingDataSource.open` recorded) — **never** `loadEventInfo.uri` (the post-redirect resolved URI). Keying on `loadEventInfo.uri` silently misses TTFB on every CDN-redirected stream. `SegmentMetric.uri` still stores `loadEventInfo.uri` for display purposes; only the registry lookup is affected.
- **`TimingDataSourceFactory` must be outermost:** When the host wraps the factory with error-injection or other adapters, `wrapDataSourceFactory` must be the outermost wrapper. Inner adapters that throw inside `open()` will propagate through `TimingDataSource.open()` *before* the timing record is written — so no false TTFB is recorded on injected errors.

### Module: `app/`

Standard MVVM with Jetpack Compose. `PlayerViewModel` owns the `ExoPlayer` instance and calls `StreamProbe.attach(player)`. `MainActivity` calls `probe.show(this)` on every `onCreate` to handle recreation. `PlayerManager` / `PlaybackController` / `TrackManager` are helpers that wrap ExoPlayer operations. Navigation is handled by Jetpack Navigation Compose (`Routes.kt`). Settings are persisted via `DataStore` (`DebugSettingsRepository`).

## Publishing

Version is set in `gradle.properties` as `VERSION_NAME`. Published to Maven Central via `vanniktech/maven-publish` plugin. The publish workflow (`.github/workflows/publish-sdk.yml`) triggers on release tags.

## AI Behavioral Rules

1. **Never guess.** Ask for clarification if project requirements are ambiguous.
2. **Only touch files necessary for the requested feature.** Do not refactor unrelated code.
3. **Fix the root cause of build/lint errors; do not suppress warnings or ignore failing tests.**
4. **Do not commit without user approval.**
