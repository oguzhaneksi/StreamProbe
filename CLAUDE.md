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
| `StreamProbe.kt` | Public entry point. Owns `SessionStore`, `PlayerInterceptor`, and `OverlayManager`. Player lifecycle (`attach`/`detach`) is **independent** of Activity lifecycle (`show`/`hide`) — the `StreamProbe` instance typically lives in a `ViewModel`. |

#### Internal: interception layer

| File | Role |
|------|------|
| `internal/PlayerInterceptor.kt` | Implements both `Player.Listener` and `AnalyticsListener`. Captures track selections, segment load completions, and playback errors; maps Media3 `Format` to SDK models via `FormatExtensions`; writes to `SessionStore`. |
| `internal/FormatExtensions.kt` | Extension functions mapping `Media3.Format` to SDK model types. `toAudioTrackInfoDetecting` infers `isMuxed` from container MIME type; `toSubtitleTrackInfoDetecting` infers `SubtitleKind` (CC vs SIDECAR) from sample MIME type. |
| `internal/CdnHeaderParser.kt` | Parses HTTP response headers (case-insensitively) into `CdnHeaderInfo`. Detects CDN provider (Cloudflare, CloudFront, Fastly, Akamai) and cache status (HIT/MISS/STALE/BYPASS/UNKNOWN). |

#### Internal: state store

| File | Role |
|------|------|
| `internal/SessionStore.kt` | Thread-safe `StateFlow` store. Capped lists: 500 segment metrics, 200 switch events, 200 errors. Consecutive `DROPPED_FRAMES` events within a 5 s window are merged into one aggregated entry (stable `timestampMs` for DiffUtil). |

#### Internal: overlay

| File | Role |
|------|------|
| `internal/overlay/OverlayManager.kt` | Manages overlay lifecycle tied to `ComponentActivity`. Adds/removes `OverlayPanelView` via `addContentView`. Registers a `DefaultLifecycleObserver` that auto-calls `hide()` on `onDestroy`, preventing leaks. Persists `ViewMode` (TRACKS / SEGMENTS / SWITCHES / ERRORS) across orientation rebuilds. Collects `SessionStore` flows in a `CoroutineScope(SupervisorJob() + Dispatchers.Main)` that is cancelled on `hide()`. |
| `internal/overlay/OverlayPanelView.kt` | Fully programmatic `LinearLayout` — no XML inflation, no `R` resource references. Constructs the entire header + body hierarchy in `init`. Supports portrait (vertical stack) and landscape (left stats column / right list column) layouts. Contains `BoundedRecyclerView`, an inner `RecyclerView` subclass that caps its measured height at a computed `maxHeightPx`. Forwards orientation changes to `OverlayManager` via `onOrientationChanged` callback. |
| `internal/overlay/RenditionListAdapter.kt` | `ListAdapter` for the Tracks tab. Item type is the `RenditionListItem` sealed interface (`SectionHeader`, `Video`, `Audio`, `Subtitle`). DiffUtil identity uses `Format.id` when available, falls back to dimensions + bitrate for video and `isSameRenditionAs` for audio/subtitle. |
| `internal/overlay/SegmentTimelineAdapter.kt` / `SwitchTimelineAdapter.kt` / `ErrorTimelineAdapter.kt` | `ListAdapter` implementations for the Segments, Switches, and Errors tabs respectively. All auto-scroll to the newest item unless the user has scrolled up. |
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
| `SegmentMetric` | Per-segment timing, size, throughput, and `CdnHeaderInfo`. |
| `TrackSwitchEvent` | Sealed interface with `VideoSwitch`, `AudioSwitch`, `SubtitleSwitch`. `SubtitleSwitch.newTrack` is nullable (null = subtitle disabled). |
| `PlaybackErrorEvent` | Immutable error record. `categoryDetail: ErrorDetail?` carries structured data (e.g. `DroppedFrames` burst aggregation). `timestampMs` is stable across merges for DiffUtil. |
| `ErrorCategory` | `LOAD_ERROR`, `VIDEO_CODEC_ERROR`, `DROPPED_FRAMES`, `AUDIO_SINK_ERROR`, `AUDIO_CODEC_ERROR`. |

### Key design decisions

- **Video switch signal split:** `onVideoInputFormatChanged` (decoder-level) is the authoritative source for `VideoSwitch` events. `onDownstreamFormatChanged` only caches the selection reason into `pendingVideoSwitchReason`, which is consumed when the format change fires. This avoids duplicate events.
- **`DEFAULT` track type guard:** In `onDownstreamFormatChanged`, a `DEFAULT`-type event only updates `pendingVideoSwitchReason` when `format.width > 0 || format.height > 0`, preventing a muxed-audio `DEFAULT` event from overwriting the pending video reason.
- **Subtitle-disabled event:** When `probeTracks` finds `foundSubtitle == null` but `lastSubtitleTrack != null`, it emits a `SubtitleSwitch(newTrack = null)` with `SwitchReason.MANUAL` to record the user turning off subtitles.
- **`isSelected` flag:** Set directly from `Tracks.Group.isTrackSelected(i)` into model objects. Neither `OverlayManager` nor adapters perform secondary active-track comparisons.
- **No XML in SDK:** `OverlayPanelView` and all item views are built programmatically so the SDK has no dependency on `res/` or `R` classes from the host app.

### Module: `app/`

Standard MVVM with Jetpack Compose. `PlayerViewModel` owns the `ExoPlayer` instance and calls `StreamProbe.attach(player)`. `MainActivity` calls `probe.show(this)` on every `onCreate` to handle recreation. `PlayerManager` / `PlaybackController` / `TrackManager` are helpers that wrap ExoPlayer operations. Navigation is handled by Jetpack Navigation Compose (`Routes.kt`). Settings are persisted via `DataStore` (`DebugSettingsRepository`).

## Publishing

Version is set in `gradle.properties` as `VERSION_NAME`. Published to Maven Central via `vanniktech/maven-publish` plugin. The publish workflow (`.github/workflows/publish-sdk.yml`) triggers on release tags.

## AI Behavioral Rules

1. **Never guess.** Ask for clarification if project requirements are ambiguous.
2. **Only touch files necessary for the requested feature.** Do not refactor unrelated code.
3. **Fix the root cause of build/lint errors; do not suppress warnings or ignore failing tests.**
4. **Do not commit without user approval.**
