# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamProbe is a **Kotlin Multiplatform** debug SDK (currently Android-only; iOS planned) that hooks into Media3/ExoPlayer to surface HLS/DASH streaming diagnostics (track variants, segment metrics, CDN headers, ABR decisions, playback errors) through an in-app draggable overlay. It is **debug-only** — never enabled in release builds.

**KMP migration status:** Phase 2 complete — the `sdk/` module uses `kotlin("multiplatform")` + `com.android.kotlin.multiplatform.library` with a single `androidTarget`. The **pure core now lives in `commonMain`**: all 17 models + enums, `SessionStore`, `NetworkTimingRegistry`, `CdnHeaderParser`, `DrmSchemeDetectorCommon` (pure UUID/state mapping), the `OverlayFormatters`/`DrmFormatters` (package kept as `internal.overlay`), and the **new `internal/presenter/` package** (`OverlayViewState`/`OverlayRow`/`OverlayStatsState`/`OverlayListsState`/`ErrorIndicatorState`/`ViewMode` + `OverlayPresenter`). The Media3/ExoPlayer adapters, the overlay Views, and `DrmSchemeDetector.detectScheme` stay in `androidMain`. One `expect/actual` exists: `displayLanguage(tag)` (Android `actual` only). New common deps: `kotlinx-datetime`, `kotlinx.atomicfu`, `kotlinx-coroutines-core`. **Phase 2** moved all platform-independent overlay logic (the `ViewMode` state machine, collapse, DRM auto-fallback, error counter, header-string formatting, rendition assembly) into the common `OverlayPresenter`, which emits a single `StateFlow<OverlayViewState>`; Android `OverlayManager` is now a thin renderer (`viewState.collect { render(it) }` + click intents forwarded to the presenter). Locked by the cross-platform `OverlayPresenterTest` (commonTest). (Phase 3 — iOS targets + headless `AVPlayerProbe` — not started.)

Two Gradle modules:
- **`sdk/`** — the published library (`io.github.oguzhaneksi:streamprobe`)
- **`app/`** — demo app that exercises the SDK against real HLS/DASH streams

## Build & Development Commands

> **KMP task-name changes (Phase 0):** `assembleRelease` → `assembleAndroidMain`; `test` → `testAndroidHostTest`; `detekt` → `detektAndroidMain` + `detektAndroidHostTest`.

```bash
# Build demo APK
./gradlew :app:assembleDebug

# Build SDK AAR
./gradlew :sdk:assembleAndroidMain

# Run SDK unit tests (Robolectric; pinned to SDK 36 via robolectric.properties)
./gradlew :sdk:testAndroidHostTest

# Run a single test class
./gradlew :sdk:testAndroidHostTest --tests "com.streamprobe.sdk.internal.PlayerInterceptorTest"

# Android Lint (SDK)
./gradlew :sdk:lint

# Ktlint check
./gradlew :sdk:ktlintCheck

# Ktlint auto-fix
./gradlew :sdk:ktlintFormat

# Detekt static analysis (detektMetadataMain analyzes commonMain)
./gradlew :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain
```

Full CI gate (all checks green):

```bash
./gradlew :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```

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

### Source set layout (`sdk/src/`)

| Source set | Path | Contents |
|---|---|---|
| `commonMain` | `sdk/src/commonMain/kotlin/` | Portable core. **No `java.`/`javax.`/`android.`/`androidx.` imports allowed.** |
| `commonTest` | `sdk/src/commonTest/kotlin/` | Pure unit tests (`kotlin.test` + `kotlinx-coroutines-test`); run under `testAndroidHostTest`. No Robolectric/Mockito/Media3. |
| `androidMain` | `sdk/src/androidMain/kotlin/` | Android/Media3 adapters, overlay Views, `DrmSchemeDetector.detectScheme`, `displayLanguage` actual |
| `androidMain` | `sdk/src/androidMain/AndroidManifest.xml` | Library manifest |
| `androidHostTest` | `sdk/src/androidHostTest/kotlin/` | Media3/Robolectric/Mockito-dependent unit tests |
| `androidHostTest` | `sdk/src/androidHostTest/resources/robolectric.properties` | Pins Robolectric to `sdk=36` (Robolectric 4.16.1 maxSdkVersion=36; compile SDK=37) |
| `androidInstrumentedTest` | `sdk/src/androidInstrumentedTest/kotlin/` | Instrumented tests |

In the Architecture section below, file paths are relative to `sdk/src/<sourceSet>/kotlin/com/streamprobe/sdk/` — the pure core (`model/`, `SessionStore`, `NetworkTimingRegistry`, `CdnHeaderParser`, `DrmSchemeDetectorCommon`, `overlay/OverlayFormatters`, `overlay/DrmFormatters`) lives in `commonMain`; everything else in `androidMain`.

### Media3 `@UnstableApi`
All classes and functions that reference Media3 `@UnstableApi` types must be annotated with `@androidx.annotation.OptIn(UnstableApi::class)` (public API) or `@UnstableApi` (internal). The public `StreamProbe` class uses `@androidx.annotation.OptIn`; internal classes use `@UnstableApi` directly.

### General conventions
- `internal` visibility for everything not part of the public SDK surface.
- `data class` for all model types; `sealed interface` for type hierarchies (`TrackSwitchEvent`, `TrackListInfo`, `OverlayRow`, `ErrorDetail`).
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
   OverlayPresenter           (common: SessionStore flows + UI intents → StateFlow<OverlayViewState>)
          │
   OverlayManager             (Android: collects viewState on Main dispatcher, renders)
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
| `internal/DrmSessionTracker.kt` | Separate `AnalyticsListener` for DRM callbacks; measures license latency and dual-surfaces DRM errors to both `drmSessionEvents` and `playbackErrors`. |
| `internal/DrmSchemeDetector.kt` | Resolves `DrmScheme` from `eventTime.timeline` and maps `DrmSession.State` integers to `DrmSessionState`. |
| `internal/FormatExtensions.kt` | Extension functions mapping `Media3.Format` to SDK model types. |
| `internal/CdnHeaderParser.kt` | Parses HTTP response headers into `CdnHeaderInfo` (CDN provider + cache status). |

#### Internal: state store

| File | Role |
|------|------|
| `internal/SessionStore.kt` | Thread-safe `StateFlow` store. Capped lists: 500 segment metrics, 200 switch events, 200 errors, 200 DRM events. Consecutive `DROPPED_FRAMES` events within a 5 s window are merged into one aggregated entry (stable `timestampMs` for DiffUtil). Exposes `drmSessionEvents: StateFlow<List<DrmSessionEvent>>` and `currentDrmState: StateFlow<DrmStatusInfo?>`. |

#### Internal: presenter (commonMain)

| File | Role |
|------|------|
| `internal/presenter/OverlayViewState.kt` | Render-ready `OverlayViewState` (grouped into `OverlayStatsState` + `OverlayListsState` to stay under detekt's `LongParameterList` of 8) plus the `ViewMode` enum, the `OverlayRow` sealed interface (`SectionHeader`/`Video`/`Audio`/`Subtitle`, carrying raw track models), and `ErrorIndicatorState`. Carries pre-formatted strings for all header fields; the four timeline lists + Tracks rows stay as raw models (rendered per-platform). |
| `internal/presenter/OverlayPresenter.kt` | Platform-independent overlay logic. Owns `viewMode`/`previousViewMode`/`isCollapsed`; `start(scope)` folds the `SessionStore` flows into a single `StateFlow<OverlayViewState>`; exposes intents (`onChipSelected`, `onCollapseToggled`, `onErrorIndicatorTapped`, `onBackPressed`, `onClearErrorsClicked`). Implements the DRM auto-fallback (DRM list empties → back to `TRACKS`) and the error counter. Mutable UI-state vars are single-dispatcher-confined (Main on Android, the test dispatcher in commonTest). |

#### Internal: overlay

| File | Role |
|------|------|
| `internal/overlay/OverlayManager.kt` | Thin Android renderer of `OverlayPresenter.viewState`. Owns the genuinely-Android pieces — `addContentView`/`DefaultLifecycleObserver` lifecycle, `MotionEvent` drag, sizing, orientation rebuild, auto-scroll — and a single `viewState.collect { render(it) }` (plus a synchronous `render` after each click intent so taps respond immediately). Click handlers forward to the presenter; the share button builds an Android `Intent` from the pure `formatErrorsForExport`. Reuses one `OverlayPresenter` across rebuilds so `ViewMode` survives orientation changes. |
| `internal/overlay/OverlayPanelView.kt` | Fully programmatic `LinearLayout` (no XML, no `R` references). Supports portrait and landscape layouts; contains `BoundedRecyclerView` that caps measured height. |
| `internal/overlay/RenditionListAdapter.kt` | `ListAdapter` for the Tracks tab; item type is the common `OverlayRow` (`SectionHeader`, `Video`, `Audio`, `Subtitle`), assembled by `OverlayPresenter`. DiffUtil identity unchanged (video by id/dimensions; audio/subtitle via `isSameRenditionAs`). |
| `internal/overlay/SegmentTimelineAdapter.kt` / `SwitchTimelineAdapter.kt` / `ErrorTimelineAdapter.kt` / `DrmTimelineAdapter.kt` | `ListAdapter` implementations for the four timeline tabs; auto-scroll to newest item unless user scrolled up. |
| `internal/overlay/DrmTimelineItemView.kt` | Programmatic row for the DRM tab: index, event dot, scheme badge, label, latency, timestamp. |
| `internal/overlay/DrmFormatters.kt` | Pure formatting functions for DRM summary panel and scheme badge labels. |
| `internal/overlay/OverlayFormatters.kt` | Pure formatting functions; uses `ThreadLocal<SimpleDateFormat>` for API 23 compatibility. |

#### Models (`model/`)

| Type | Description |
|------|-------------|
| `TrackListInfo` | Sealed interface; implemented only by `TracksSnapshot`. |
| `VariantInfo` | `isSelected` from `Tracks.Group.isTrackSelected(i)`; nullable `Format.id` preferred over dimension matching in DiffUtil. |
| `AudioTrackInfo` | `isMuxed = true` when audio is muxed into the video container. |
| `SubtitleTrackInfo` | `kind` distinguishes `SIDECAR` (WebVTT/TTML) from `CC` (CEA-608/CEA-708). |
| `ActiveTrackInfo` | Currently decoded video track. Distinct from `VariantInfo` — sourced from the decoder, not the track group. |
| `NetworkTiming` | Best-effort TTFB breakdown. Per-phase fields (`dnsMs?`, `connectMs?`, `tlsMs?`) reserved; `isEstimated` flag when TTFB is inferred. |
| `TrackSwitchEvent` | Sealed interface with `VideoSwitch`, `AudioSwitch`, `SubtitleSwitch`. `SubtitleSwitch.newTrack` is nullable (null = subtitle disabled). |
| `PlaybackErrorEvent` | `categoryDetail: ErrorDetail?` carries structured data (e.g. `DroppedFrames` burst, `DrmErrorInfo`). `timestampMs` is stable across merges for DiffUtil. |
| `DrmSessionEvent` | Sealed interface with `SessionAcquired`, `KeysLoaded` (+ `licenseLatencyMs`), `SessionReleased`, `SessionError`. Each subtype has a monotonically increasing `id` for stable DiffUtil identity. |

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

Version is set in `gradle.properties` as `VERSION_NAME`. Published to Maven Central via `vanniktech/maven-publish` plugin (v0.36.0 — auto-detects KMP; wires up the `release` AAR + the root `streamprobe` metadata module so consumers using `io.github.oguzhaneksi:streamprobe:x.y.z` resolve correctly). The publish workflow (`.github/workflows/publish-sdk.yml`) triggers on release tags.

## AI Behavioral Rules

1. **Never guess.** Ask for clarification if project requirements are ambiguous.
2. **Only touch files necessary for the requested feature.** Do not refactor unrelated code.
3. **Fix the root cause of build/lint/ktlint/detekt errors; do not suppress warnings or ignore failing tests.**
4. **Do not commit without user approval.**
