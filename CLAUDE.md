# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamProbe is a **Kotlin Multiplatform** debug SDK (Android + headless iOS; iOS overlay planned for Phase 4) that hooks into Media3/ExoPlayer on Android and AVFoundation on iOS to surface HLS/DASH streaming diagnostics (track variants, segment metrics, CDN headers, ABR decisions, playback errors) through an in-app draggable overlay on Android. It is **debug-only** — never enabled in release builds.

Two Gradle modules:
- **`sdk/`** — the published library (`io.github.oguzhaneksi:streamprobe`)
- **`app/`** — demo app that exercises the SDK against real HLS/DASH streams

**KMP layout:** the `sdk/` module uses `kotlin("multiplatform")` + `com.android.kotlin.multiplatform.library` with Android + iOS targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`). The **pure core lives in `commonMain`** (models + enums, `SessionStore`, `NetworkTimingRegistry`, `CdnHeaderParser`, `DrmSchemeDetectorCommon`, the `internal/overlay` formatters, and the `internal/presenter` package). Media3 adapters, overlay Views, and `DrmSchemeDetector.detectScheme` are in `androidMain`; AVFoundation adapters and `AVPlayerProbe` are in `iosMain`. **Two `expect/actual` pairs:** `displayLanguage(tag)` and `StreamProbe` itself (each platform `actual` constructs its own `SessionStore` — no shared singleton; the `expect` class declares only `fun detach()`). Requires `-Xexpect-actual-classes` (Beta in Kotlin 2.3+). Common deps: `kotlinx-datetime`, `kotlinx.atomicfu`, `kotlinx-coroutines-core`. Locked by `OverlayPresenterTest` (commonTest) + iOS mapper tests (`iosTest`). Migration history is in the memory files (`project_kmp_phase*_findings.md`).

## Build & Development Commands

```bash
# Build demo APK
./gradlew :app:assembleDebug

# Build SDK AAR
./gradlew :sdk:assembleAndroidMain

# Run SDK unit tests (Robolectric; pinned to SDK 36 via robolectric.properties)
./gradlew :sdk:testAndroidHostTest

# Run a single test class
./gradlew :sdk:testAndroidHostTest --tests "com.streamprobe.sdk.internal.PlayerInterceptorTest"

# Run iOS unit tests (requires Xcode + iosSimulatorArm64 toolchain)
./gradlew :sdk:iosSimulatorArm64Test

# Android Lint / Ktlint / Detekt (detektMetadataMain analyzes commonMain)
./gradlew :sdk:lint
./gradlew :sdk:ktlintCheck        # ktlintFormat to auto-fix
./gradlew :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain
```

Full CI gate (all checks green):

```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```

## Code & Formatting Standards

All code must pass `ktlintCheck`, `detekt`, and `lint` before merging. `detekt` is `maxIssues: 0` — zero tolerance for new issues. **Fix the root cause; never suppress with `@Suppress` or `// detekt:disable`.**

### Ktlint
- Official Kotlin coding style (v14.2.0). Run `ktlintFormat` to auto-fix before committing.
- Wildcard imports forbidden.
- Also lints `iosMain`/`iosTest`. iOS rules to watch: `no-consecutive-comments` (a KDoc may not be immediately followed by a `//` comment or another KDoc — use a `/* */` block for file-level overviews); `class-signature` (a single primary-constructor parameter goes on its own line).

### Detekt
Rules and thresholds live in `config/detekt/detekt.yml` — consult that file. Composable and test functions are exempt from `FunctionNaming` (uppercase names allowed).

### Source set layout (`sdk/src/`)

| Source set | Contents |
|---|---|
| `commonMain` | Portable core. **No `java.`/`javax.`/`android.`/`androidx.` imports allowed.** |
| `commonTest` | Pure unit tests (`kotlin.test` + `kotlinx-coroutines-test`); run under both `testAndroidHostTest` and `iosSimulatorArm64Test`. No Robolectric/Mockito/Media3. |
| `androidMain` | Android/Media3 adapters, overlay Views, `DrmSchemeDetector.detectScheme`, `displayLanguage`/`StreamProbe` actuals. Library manifest at `androidMain/AndroidManifest.xml`. |
| `androidHostTest` | Media3/Robolectric/Mockito unit tests. `resources/robolectric.properties` pins Robolectric to `sdk=36` (Robolectric 4.16.1 maxSdkVersion=36; compile SDK=37). |
| `androidInstrumentedTest` | Instrumented tests. |
| `iosMain` | AVFoundation adapters (`AVPlayerProbe`, `AVMetricMappers`, `AVAccessLogMappers`), `LanguageNames.ios.kt`, `StreamProbe.ios.kt` actual. **No Android/JVM imports.** |
| `iosTest` | iOS unit tests: pure mapper tests + `AVPlayerProbePocTest`. |

Architecture file paths below are relative to `sdk/src/<sourceSet>/kotlin/com/streamprobe/sdk/`.

### Conventions
- `internal` visibility for everything not part of the public SDK surface.
- `data class` for model types; `sealed interface` for type hierarchies (`TrackSwitchEvent`, `TrackListInfo`, `OverlayRow`, `ErrorDetail`).
- **No XML layouts in the SDK** — all `overlay/` views are built programmatically so the library needs no `res/` or `R` classes from the host app.
- `String.format(Locale.ROOT, ...)` for all locale-sensitive formatting (enforced by lint).
- Media3 `@UnstableApi` types: annotate public API with `@androidx.annotation.OptIn(UnstableApi::class)`, internal classes with `@UnstableApi`.

## Architecture

### Data flow

**Android:**
```
ExoPlayer ─ Player.Listener / AnalyticsListener
   → PlayerInterceptor   (maps Media3 events → SDK models)
   → SessionStore        (StateFlow in-memory store)
   → OverlayPresenter    (common: store flows + UI intents → StateFlow<OverlayViewState>)
   → OverlayManager      (Android: collects viewState on Main.immediate, renders)
   → OverlayPanelView    (programmatic View hierarchy, added via addContentView)
```

**iOS (headless):**
```
AVPlayer ─ NSNotificationCenter (access/error-log notifications, scoped to currentItem)
   → AVPlayerProbe       (maps AVFoundation events → SDK models)
   → SessionStore        (same common store; observable by Phase 4 overlay)
```

### `sdk/` — files

**Public surface — `StreamProbe.kt`:** `expect class StreamProbe()` in `commonMain` (only common member: `fun detach()`). **Android actual** owns `SessionStore`, `NetworkTimingRegistry`, `PlayerInterceptor`, `OverlayManager`; player lifecycle (`attach`/`detach`) is independent of Activity lifecycle (`show`/`hide`), so the instance typically lives in a `ViewModel`; `wrapDataSourceFactory(factory)` wraps the factory in `TimingDataSourceFactory` and must be the **outermost** wrapper. **iOS actual** owns `SessionStore` + `AVPlayerProbe`; exposes `fun attach(player: AVPlayer)` + `internal val sessionStore` (headless — no overlay until Phase 4).

**Interception (`androidMain/internal/`):**
- `PlayerInterceptor.kt` — `Player.Listener` + `AnalyticsListener`. Captures track selections, segment loads, playback errors; maps `Format` via `FormatExtensions`; writes to `SessionStore`; registers/unregisters `DrmSessionTracker`. In `onLoadCompleted`, folds TTFB from `NetworkTimingRegistry` (see redirect-key invariant below).
- `TimingDataSourceFactory.kt` — `@UnstableApi` `DataSource.Factory` wrapper. Times `open()` as a TTFB proxy for HTTP/HTTPS only; records in the registry keyed by request URI + byte position. No entry written if `open()` throws. Uses `SystemClock.elapsedRealtimeNanos()` to match `loadDurationMs`' clock domain.
- `DrmSessionTracker.kt` — separate `AnalyticsListener` for DRM; measures license latency, dual-surfaces DRM errors to `drmSessionEvents` + `playbackErrors`.
- `DrmSchemeDetector.kt` — resolves `DrmScheme` from `eventTime.timeline`; maps `DrmSession.State` ints to `DrmSessionState`.
- `FormatExtensions.kt` — `Media3.Format` → SDK models.

**Core (`commonMain/internal/`):**
- `NetworkTimingRegistry.kt` — bounded, thread-safe handoff (I/O-thread writer ↔ playback-thread reader). Keys as `"uri|position"` (pipe delimiter avoids `@`-in-URI collisions). FIFO eviction at `MAX_ENTRIES = 128`.
- `CdnHeaderParser.kt` — HTTP headers → `CdnHeaderInfo` (provider + cache status).
- `SessionStore.kt` — thread-safe `StateFlow` store. Capped: 500 segment metrics, 200 each of switch events / errors / DRM events. Consecutive `DROPPED_FRAMES` within 5 s merge into one entry (stable `timestampMs` for DiffUtil). Exposes `drmSessionEvents` + `currentDrmState`.
- `presenter/OverlayViewState.kt` — render-ready `OverlayViewState` (`mode`, `isCollapsed`, `stats`, `lists`, `errorIndicator`, `isErrorsMode`, `errorsTitle`; grouped into `OverlayStatsState`/`OverlayListsState` to stay under `LongParameterList` of 8) + `ViewMode` enum + `OverlayRow` sealed interface (`SectionHeader`/`Video`/`Audio`/`Subtitle`, raw track models) + `ErrorIndicatorState`. Header fields pre-formatted; timeline lists + Tracks rows stay raw (rendered per-platform).
- `presenter/OverlayPresenter.kt` — platform-independent overlay logic. Owns `viewMode`/`previousViewMode`/`isCollapsed`; `start(scope)` folds `SessionStore` flows into one `StateFlow<OverlayViewState>`; exposes intents (`onChipSelected`, `onCollapseToggled`, `onErrorIndicatorTapped`, `onBackPressed`, `onClearErrorsClicked`). Implements DRM auto-fallback (DRM list empties → `TRACKS`) + error counter. Mutable UI-state vars are single-dispatcher-confined (Main on Android, test dispatcher in commonTest).

**Overlay (`androidMain/internal/overlay/`, formatters in `commonMain`):**
- `OverlayManager.kt` — thin Android renderer of `OverlayPresenter.viewState`. Owns the Android pieces (`addContentView`/lifecycle, `MotionEvent` drag, sizing, orientation rebuild, auto-scroll) + one `viewState.collect { render(it) }` on `Dispatchers.Main.immediate` (fires synchronously on the main thread → instant tap reflection). Click handlers forward to the presenter; share button builds an `Intent` from the pure `formatErrorsForExport`. Reuses one `OverlayPresenter` across rebuilds so `ViewMode` survives rotation. Attaches the target adapter before `submitList()` so the initial-load auto-scroll guard holds.
- `OverlayPanelView.kt` — programmatic `LinearLayout`, portrait + landscape; contains `BoundedRecyclerView` capping measured height.
- `RenditionListAdapter.kt` — Tracks tab; item type is the common `OverlayRow`. DiffUtil: video by id/dimensions, audio/subtitle via `isSameRenditionAs`.
- `SegmentTimelineAdapter` / `SwitchTimelineAdapter` / `ErrorTimelineAdapter` / `DrmTimelineAdapter` — the four timeline tabs; auto-scroll to newest unless user scrolled up.
- `DrmFormatters.kt` (common) — DRM summary + scheme-badge labels. `OverlayFormatters.kt` (common) — uses `ThreadLocal<SimpleDateFormat>` for API 23 compat.

**iOS interception (`iosMain/internal/`):**
- `AVPlayerProbe.kt` — attaches to an `AVPlayer`. Discovers tracks via `AVURLAsset.loadValuesAsynchronouslyForKeys(["variants"])` (checks `statusOfValueForKey` + verifies `currentItem?.asset === asset` to reject stale closures). Observes new-access/error-log notifications via `NSNotificationCenter.addObserverForName(…, object: player.currentItem, …)`. Bitrate switch = `indicatedBitrate` change across entries → `VideoSwitch`. Calls `store.clear()` at `attach()`. Observer tokens (`NSObjectProtocol`) removed in `detach()`.
- `AVMetricMappers.kt` / `AVAccessLogMappers.kt` — pure mappers: `AVAssetVariant` → `VariantInfo`, `AVMediaSelectionOption` → audio/subtitle tracks; access-log throughput (prefers `observedBitrate/8`), `accessLogSegmentMetric` (TTFB null), bitrate-switch detection, dropped-frames/load errors. Uses `playbackStartDate?.timeIntervalSince1970` as `requestTimestampMs`. (`IosConstants.kt`: shared `MILLIS_PER_SECOND`.)

### Models (`commonMain/model/`) — notable

| Type | Note |
|------|------|
| `VariantInfo` | `isSelected` from `Tracks.Group.isTrackSelected(i)`; nullable `Format.id` preferred over dimension matching in DiffUtil. |
| `AudioTrackInfo` | `isMuxed = true` when audio is muxed into the video container. |
| `SubtitleTrackInfo` | `kind`: `SIDECAR` (WebVTT/TTML) vs `CC` (CEA-608/708). |
| `ActiveTrackInfo` | Currently decoded video track — sourced from the decoder, not the track group (distinct from `VariantInfo`). |
| `NetworkTiming` | Best-effort TTFB. Per-phase fields (`dnsMs?`/`connectMs?`/`tlsMs?`) reserved; `isEstimated` when inferred. |
| `TrackSwitchEvent` | Sealed: `VideoSwitch`/`AudioSwitch`/`SubtitleSwitch`. `SubtitleSwitch.newTrack` nullable (null = disabled). |
| `PlaybackErrorEvent` | `categoryDetail: ErrorDetail?` (e.g. `DroppedFrames` burst, `DrmErrorInfo`). `timestampMs` stable across merges. |
| `DrmSessionEvent` | Sealed: `SessionAcquired`/`KeysLoaded` (+`licenseLatencyMs`)/`SessionReleased`/`SessionError`. Monotonic `id` for DiffUtil. |

### Key design decisions & invariants

- **TTFB redirect-key invariant:** In `PlayerInterceptor.onLoadCompleted`, the registry lookup **must** use `loadEventInfo.dataSpec.uri` (the pre-redirect URI `TimingDataSource.open` recorded) — **never** `loadEventInfo.uri` (post-redirect), which silently misses TTFB on every CDN-redirected stream. `SegmentMetric.uri` still stores `loadEventInfo.uri` for display.
- **`TimingDataSourceFactory` must be outermost:** so error-injection/other inner adapters that throw in `open()` propagate *before* the timing record is written — no false TTFB on injected errors.
- **Video switch signal split:** `onVideoInputFormatChanged` (decoder-level) is authoritative for `VideoSwitch`. `onDownstreamFormatChanged` only caches the reason into `pendingVideoSwitchReason`, consumed when the format change fires — avoids duplicate events. A `DEFAULT`-type downstream event updates the pending reason only when `format.width > 0 || format.height > 0` (guards against a muxed-audio `DEFAULT` overwriting the video reason).
- **Subtitle-disabled event:** when `probeTracks` finds `foundSubtitle == null` but `lastSubtitleTrack != null`, it emits `SubtitleSwitch(newTrack = null, MANUAL)`.
- **`isSelected`** is set directly from `Tracks.Group.isTrackSelected(i)` — no secondary active-track comparison in the manager or adapters.
- **DRM tracker isolation:** DRM callbacks live in a separate `DrmSessionTracker` `AnalyticsListener` (keeps `PlayerInterceptor` under `TooManyFunctions` while staying cohesive).
- **DRM scheme from timeline:** `detectScheme` reads `eventTime.timeline`, not `player.currentMediaItem`, to avoid a playlist-transition race.
- **DRM dual surface:** `onDrmSessionManagerError` writes to both `drmSessionEvents` and `playbackErrors` via a single write path (no duplication).
- **iOS — `AVURLAsset` required for `variants`:** `variants` is an extension on `AVURLAsset`, not `AVAsset`. Always cast `item.asset as? AVURLAsset`.
- **iOS — observers scoped to `currentItem`:** prevents sibling-player / `AVQueuePlayer` callbacks from firing this probe's handlers (item-change tracking deferred to Phase 4).
- **iOS — access-log entries processed one notification behind:** the probe reads `accessLog()?.events?.dropLast(1)` so only finalized (non-mutating) entries are read; the last is picked up by the next notification.
- **iOS — monotonic timestamps:** `nowMs()` uses `CACurrentMediaTime()` + an epoch offset computed once at `attach()`, avoiding wall-clock non-monotonicity.
- **iOS Simulator TLS limitation (not a code defect):** the sandboxed simulator can't complete TLS (process-isolated network daemon), so HTTPS AVPlayer streams fail with `status=2`. `AVPlayerProbePocTest` skips the live leg with a loud log; pure mapper tests give hermetic coverage. Tests pass on machines with working simulator network.

### `app/`

Standard MVVM + Jetpack Compose. `PlayerViewModel` owns the `ExoPlayer` and calls `StreamProbe.attach(player)`. `MainActivity` calls `probe.show(this)` on every `onCreate` to handle recreation. `PlayerManager`/`PlaybackController`/`TrackManager` wrap ExoPlayer operations. Navigation via Jetpack Navigation Compose (`Routes.kt`); settings persisted via `DataStore` (`DebugSettingsRepository`).

## Publishing

Version in `gradle.properties` (`VERSION_NAME`). Published to Maven Central via `vanniktech/maven-publish` (v0.36.0 — auto-detects KMP; wires the `release` AAR + root `streamprobe` metadata module so `io.github.oguzhaneksi:streamprobe:x.y.z` resolves). Publish workflow (`.github/workflows/publish-sdk.yml`) triggers on release tags.

## AI Behavioral Rules

1. **Never guess.** Ask for clarification if requirements are ambiguous.
2. **Only touch files necessary for the requested feature.** Do not refactor unrelated code.
3. **Fix the root cause of build/lint/ktlint/detekt errors; do not suppress warnings or ignore failing tests.**
4. **Do not commit without user approval.**
