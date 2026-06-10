# StreamProbe → Kotlin Multiplatform (Android + iOS) — Architecture & Phased Migration Roadmap

> **Document type:** This is the program-level **architecture + migration roadmap**, not a line-by-line implementation plan. Each phase is broken into trackable sub-tasks with file targets and an exit gate. When a phase is actually started, it should be expanded into its own bite-sized, TDD-style executable plan (one plan per phase) — the granular per-step code can only be written honestly once the preceding phase has landed (this is especially true for the unproven iOS AVFoundation interop in Phases 3–5).

## Context

StreamProbe today is an **Android-only** debug SDK bound deeply to Media3/ExoPlayer. Goal: migrate the SDK **incrementally** to Kotlin Multiplatform and deliver the same diagnostics experience on iOS (via AVPlayer). This document clarifies feasibility and lays out a low-risk roadmap.

**Fixed user decisions (framing):**
1. **Motivation = exploration/learning.** Low risk, incremental; prove feasibility with a working slice (PoC). No over-engineering.
2. **UI = shared core + native UI.** Android keeps its existing programmatic Views. The iOS SDK overlay is **programmatic UIKit** (a mirror of Android's "no XML, everything in code" philosophy). The iOS *host app* is written in SwiftUI and consumes the SDK.
3. **iOS accuracy = reduced acceptance.** Based on `AVPlayerItemAccessLog`/`ErrorLog` + KVO + `AVContentKeySession` (FairPlay). No per-segment TTFB → `NetworkTiming.isEstimated` is used. The heavy local-proxy / resource-loader path is **out of scope**.

**Core insight:** The codebase is, unintentionally, already shaped like **ports-and-adapters**. All Android/Media3 coupling is concentrated at two ends — the **write side** (9 Media3 callbacks + DataSource timing + DRM listener) and the **render side** (the overlay Views). The core in between (`SessionStore` + pure models + parsers + formatters) is already platform-independent in spirit. KMP merely formalizes this. Expected result: apart from a single `expect/actual` (language display names, which can itself be deferred), everything is solved in `commonMain` with `kotlinx-datetime` + `atomicfu`.

---

## Target Architecture

The central hub is `SessionStore`: a pure `MutableStateFlow` aggregator with a clean `addX()/updateX()` write API and a `StateFlow` read API. Both `PlayerInterceptor` (Android) and the new `AVPlayerProbe` (iOS) are platform adapters that **write** to this hub. `OverlayPresenter` (new, common) turns the hub into a render-ready `ViewState`; the Android Views and iOS UIKit are **thin renderers** of it.

```
commonMain  (portable core)
├── model/            17 models + enums (move verbatim; fix DrmSessionEvent id-gen)
│                     + NEW: DrmScheme.FAIRPLAY enum constant
├── internal/
│   ├── SessionStore.kt          (verbatim; absorb DRM id-gen; drop @VisibleForTesting)
│   ├── NetworkTimingRegistry.kt (synchronized → atomicfu lock; idle on iOS)
│   ├── CdnHeaderParser.kt        (pure; Locale.ROOT.lowercase → lowercase())
│   └── DrmSchemeDetectorCommon   (mapUuidToScheme/mapDrmState — the pure half)
└── internal/presenter/  (NEW)
    ├── OverlayPresenter.kt       (ViewMode state machine, collapse, filtering, DRM-fallback)
    ├── OverlayViewState.kt       (fully-formatted, render-ready data classes)
    ├── OverlayFormatters.kt      (move; dates via kotlinx-datetime)
    └── DrmFormatters.kt          (verbatim — already pure)

androidMain  (Media3 + existing Views — almost unchanged)
├── StreamProbe.android.kt (actual): attach(ExoPlayer), show(ComponentActivity), wrapDataSourceFactory
├── internal/  PlayerInterceptor, DrmSessionTracker, DrmSchemeDetector.detectScheme(),
│              TimingDataSourceFactory, FormatExtensions   (all @UnstableApi, never leak to common)
└── internal/overlay/  ~2500 lines of View — OverlayManager drops to a thin layer that renders OverlayViewState

iosMain  (AVFoundation + UIKit — new)
├── StreamProbe.ios.kt (actual): attach(AVPlayer), show(UIView/UIWindow)
├── internal/  AVPlayerProbe.kt (accessLog/errorLog/KVO/ContentKey → SessionStore), AVMetricMappers.kt
└── internal/overlay/  programmatic UIKit renderer (preferably Swift, consumes OverlayPresenter)

commonTest  pure tests move here (SessionStore, NetworkTimingRegistry, CdnHeaderParser,
            DRM mapping, Formatters + NEW OverlayPresenterTest — cross-platform contract)
androidUnitTest  Robolectric/Mockito/Media3-dependent tests stay (PlayerInterceptorTest, etc.)
```

---

## Decisions Table

| # | Decision | Choice |
|---|---|---|
| D1 | Migration style | **In-place strangler** inside the existing `sdk` module; Phase 0 = KMP plugin + androidTarget only, zero behavior change |
| D2 | Core boundary | `SessionStore` + models + pure helpers + `OverlayPresenter` → commonMain; the two player adapters **write** to it |
| D3 | PoC slice | `AVAssetVariant` track/variant listing end-to-end (cheapest, highest signal) |
| D4 | AtomicLong | Move id-gen into `SessionStore` (no expect/actual) |
| D5 | Registry lock | `kotlinx.atomicfu` lock in common (no expect/actual) |
| D6 | Date formatting | `kotlinx-datetime` `Format {}` DSL → **eliminates** the SimpleDateFormat/ThreadLocal expect/actual |
| D7 | Language display names | **Single** expect/actual; deferred (raw BCP-47 tag fallback on iOS until Phase 5) |
| D8 | @VisibleForTesting | Drop (internal + commonTest see the same module) |
| D9 | iOS adapter language | **Kotlin/Native cinterop** in iosMain (`platform.AVFoundation.*`); thin Swift shim allowed only for `AVContentKeySession` |
| D10 | UI architecture | `OverlayPresenter` → `StateFlow<OverlayViewState>`; Android View + iOS UIKit thin renderers (K/N building UIKit was **rejected**) |
| D11 | iOS overlay impl language | Swift/UIKit, consumes the Kotlin presenter (UIKit ergonomics + SKIE) |
| D12 | Distribution | AAR via vanniktech (unchanged); iOS XCFramework via **SPM**; SKIE in Phase 4; separate SwiftUI iOS demo app |
| **D13** | **iOS deployment base** | **iOS 15** — clean `AVAssetVariant`; no 13/14 degrade-variant fallback (resolves risk #4) |
| **D14** | **FairPlay DRM timing** | **Deferred** to Phase 5, gated on license-server + cert availability; **not** in the first iOS feature-complete pass |
| **D15** | **iOS overlay window** | **Separate root-level `UIWindow`** at a high `windowLevel` — production players may not be full-screen, so confining the overlay to a small player layer is wrong; residual hit-test passthrough handled in Phase 4 |

---

## Phased Migration (strangler — Android stays green/publishable at every phase)

Each phase below lists trackable sub-tasks (`- [ ]`) and an **exit gate**. Sub-tasks are at roadmap altitude (file target + change + exit criterion); expand a phase into a granular TDD plan when you start it.

### Phase 0 — KMP plugin, androidTarget only (zero behavior change)

Add the KMP plugin without moving logic, to flush the new Kotlin 2.3.20 + AGP 9.1.0 toolchain risk *before* any code moves.

- [x] **0.1** Add `kotlin-multiplatform` and `android-kotlin-multiplatform-library` plugins to the version catalog and root `build.gradle.kts` (`apply false`). *Actual:* AGP 9.0+ forbids `com.android.library` + `kotlin("multiplatform")` together — the required plugin is `com.android.kotlin.multiplatform.library` (replaces `com.android.library` in the KMP combination).
- [x] **0.2** Apply `kotlin("multiplatform")` + `com.android.kotlin.multiplatform.library` in `sdk/build.gradle.kts`. Android library config (`namespace`, `compileSdk`, `minSdk`, `compilerOptions`) lives inside `kotlin { android { } }` — **not** in a top-level `android { }` block (that extension does not exist with the new plugin). Unit-test compilation is enabled via `withHostTest { isIncludeAndroidResources = true }` (equivalent to old `testOptions.unitTests.isIncludeAndroidResources`). `publishLibraryVariants("release")` is set on the `android {}` target via vanniktech's KMP auto-wiring. *Exit:* `./gradlew :sdk:tasks` shows KMP tasks including `bundleAndroidMainAar`.
- [x] **0.3** Move all production deps into `androidMain.dependencies { }`; test deps into `val androidHostTest by getting { dependencies { } }` (the KMP host-test source set). `commonMain` left minimal. *Exit:* dependency resolution OK.
- [x] **0.4** Relocate source roots: `src/main/java/**` → `src/androidMain/kotlin/**`, `src/test/java/**` → `src/androidHostTest/kotlin/**` (the new plugin names the unit-test source set `androidHostTest`, not `androidUnitTest`), `src/androidTest/java/**` → `src/androidInstrumentedTest/kotlin/**`, `src/main/AndroidManifest.xml` → `src/androidMain/`. Package paths unchanged. *Exit:* files relocated, no package edits.
- [x] **0.5** vanniktech 0.36.0 auto-detects KMP. The Android publication artifact id becomes `streamprobe-android` (standard KMP convention); the root `streamprobe` module's `module.json` redirects Gradle variant selection to it, so consumers using `io.github.oguzhaneksi:streamprobe:0.5.0` resolve correctly. Project `version` and `group` set at the build-script level (pre-plugins block) to avoid a finalized-property conflict. Signing requires GPG keys; publishing works in CI.
- [x] **0.6** Regression gate.

> **AGP 9.x task-name changes:** `assembleRelease` → `assembleAndroidMain` / `bundleAndroidMainAar`; `test` → `testAndroidHostTest`; `detekt` (was NO-SOURCE in KMP) → `detektAndroidMain` + `detektAndroidHostTest`. Updated detekt.yml to exclude test source sets from `TooManyFunctions`, `UnsafeCallOnNullableType`, and `MapGetWithNotNullAssertionOperator` (matching old behavior where tests were not in detekt scope). Also fixed 10 pre-existing detekt issues surfaced by the new type-resolution analysis: `!!`-double-lookup in `CdnHeaderParser`, `!!`-on-just-assigned-nullable in `OverlayManager`, and `?: ""` → `.orEmpty()` in `RenditionItemView`.

**Exit gate (actual commands):** `./gradlew :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :app:assembleDebug` — all green. AAR produced at `sdk/build/outputs/aar/sdk.aar`. Zero behavior change; toolchain risk eliminated.

### Phase 1 — Pure core down to commonMain

- [ ] **1.1** Add `kotlinx-datetime` + `kotlinx.atomicfu` to the version catalog and `commonMain` deps.
- [ ] **1.2** Move the 17 models + enums to `commonMain/.../model/` verbatim (same package). Add `DrmScheme.FAIRPLAY`. *Exit:* common compiles.
- [ ] **1.3** Fix `DrmSessionEvent` id-gen (D4): remove the `AtomicLong` from the model; pass `id` as a constructor param assigned by the store. *Exit:* model compiles in common.
- [ ] **1.4** Move `SessionStore.kt` to commonMain; assign DRM event ids inside `addDrmSessionEvent()` (single writer, already serialized); drop `@VisibleForTesting` (D8). *Exit:* common compiles; no JVM-only APIs referenced.
- [ ] **1.5** Move `NetworkTimingRegistry.kt`; replace `synchronized` with a `kotlinx.atomicfu` lock (D5); keep the `"uri|position"` key + FIFO eviction at `MAX_ENTRIES = 128`. *Exit:* common compiles.
- [ ] **1.6** Move `CdnHeaderParser.kt`; replace `Locale.ROOT.lowercase()` with Kotlin's locale-independent `lowercase()`. *Exit:* common compiles; parser tests still pass.
- [ ] **1.7** Split `DrmSchemeDetector`: pure half (`mapUuidToScheme`/`mapDrmState`) → commonMain (`DrmSchemeDetectorCommon`); `detectScheme(eventTime.timeline)` stays in androidMain. *Exit:* common compiles.
- [ ] **1.8** Move `OverlayFormatters` + `DrmFormatters` to `commonMain/presenter/`; replace `SimpleDateFormat`/`ThreadLocal` with `kotlinx-datetime` `LocalDateTime.Format { }` producing `HH:mm:ss.SSS` (D6). *Exit:* formatter output byte-identical (locked by moved tests).
- [ ] **1.9** Move the pure tests to commonTest (SessionStore, NetworkTimingRegistry, CdnHeaderParser, DRM mapping, Formatters). *Exit:* they run as commonTest on the JVM.

**Exit gate:** AAR behavior identical; pure tests run as commonTest on JVM; the core compiles in common with JVM APIs forbidden — proving it is genuinely portable. Call sites unchanged (same packages).

### Phase 2 — Extract OverlayPresenter; reduce OverlayManager to a renderer

Move all platform-independent logic out of `OverlayManager` (currently 473 lines) into a common presenter that emits a single `StateFlow<OverlayViewState>`.

- [ ] **2.1** Define `OverlayViewState` + the `OverlayRow` sealed interface + `ErrorIndicatorState` in `commonMain/presenter/`. *Exit:* common compiles.
- [ ] **2.2** Create the `OverlayPresenter` skeleton: consumes `SessionStore` flows, exposes a dispatcher-agnostic `StateFlow<OverlayViewState>`. *Exit:* common compiles.
- [ ] **2.3** Move the `ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }` state machine + `previousViewMode` + collapse into the presenter; expose `onChipSelected(...)` / `onCollapseToggled()`. Write `OverlayPresenterTest` (commonTest) for mode transitions + collapse.
- [ ] **2.4** Move the DRM auto-fallback (`observeDrm`: DRM list empties → return to TRACKS) into the presenter. Test the transition.
- [ ] **2.5** Move the error-indicator text/visibility + header counter (`observePlaybackErrors`) into the presenter. Test the counter contract.
- [ ] **2.6** Move rendition-list assembly (`SectionHeader + items → RenditionListItem`/`OverlayRow`) and all formatter calls into the presenter — `OverlayViewState` carries **pre-formatted strings, not raw models**. Test.
- [ ] **2.7** Refactor Android `OverlayManager`: replace the 10 `collect` calls with a single `presenter.viewState.collect { render(it) }`. Keep `show/hide`, `addContentView`, lifecycle observer, `MotionEvent` drag, size/orientation rebuild (genuinely Android). `render(state)` sets TextViews, toggles visibility, `submitList`. The share button still builds an Android `Intent` but takes its text from `formatErrorsForExport` (already pure). *Exit:* Android overlay behaves identically (manual + existing Robolectric tests).

**Exit gate:** Android overlay behaves byte-for-byte the same; `OverlayPresenterTest` locks the contract. **Highest-leverage phase** — it makes the iOS UI cheap and validates the contract on Android first.

### Phase 3 — iOS targets + headless AVPlayerProbe (PoC — no UI yet)

This is the **feasibility proof**. It depends only on Phase 1 (see dependency graph) — it does **not** wait for Phase 2.

- [ ] **3.1** Add `iosArm64/iosSimulatorArm64/iosX64` targets; `binaries.framework { baseName = "StreamProbe"; isStatic = true }`; set iOS deployment target **15** (D13). *Exit:* `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` produces a framework.
- [ ] **3.2** `StreamProbe.ios.kt` actual: `attach(AVPlayer)`, headless (no `show` yet). Owns `SessionStore` + `AVPlayerProbe`. *Exit:* framework links.
- [ ] **3.3** **PoC milestone — `AVAssetVariant` track/variant listing end-to-end.** `AVMetricMappers` maps `AVAssetVariant` (peak/avg bitrate, `presentationSize`, codecs) → `VariantInfo`, and `AVMediaSelectionGroup` (audio/legible) → `AudioTrackInfo`/`SubtitleTrackInfo`; calls `SessionStore.updateTrackList()`. A small Swift/XCTest harness creates an `AVPlayer` with an HLS stream, attaches the probe, and dumps `sessionStore.trackList.value` to the console. *Exit:* a real `VariantInfo` list is observed from Swift → **the whole chain (cinterop → model → store → Swift read) is proven**.
- [ ] **3.4** Access-log segment metrics: observe `AVPlayerItemAccessLogEvent` (bytesTransferred, observedBitrate, uri, durationWatched, serverAddress) → `addSegmentMetric` (rough/roll-up, `networkTiming` null or `isEstimated = true`); `serverAddress` → `CdnHeaderInfo(cacheStatus = UNKNOWN, cdnProvider = null)`.
- [ ] **3.5** Access-log bitrate switches: `indicatedBitrate`/`switchBitrate` delta (KVO) → `addTrackSwitchEvent(VideoSwitch)` + `updateActiveTrack` (reason `ADAPTIVE`/`UNKNOWN`).
- [ ] **3.6** Error + dropped-frames: `AVPlayerItemErrorLogEvent` → `addPlaybackError(LOAD_ERROR, status, domain)`; access-log `numberOfDroppedVideoFrames` → `addPlaybackError(DROPPED_FRAMES)` (reuses the existing 5 s burst dedup in `SessionStore`).

> FairPlay DRM is **deferred** (D14) — it is **not** in Phase 3.

**Exit gate (feasibility):** Real AVFoundation data lands in the shared `SessionStore` and is observable from Swift — the full chain is proven **without any UI investment**.

### Phase 4 — Programmatic UIKit overlay on iOS (separate `UIWindow`)

- [ ] **4.1** Overlay window: a separate root-level `UIWindow` subclass at a high `windowLevel` (D15), with **hit-test passthrough** — touches outside the overlay's own subviews fall through to the host app. *Exit:* the overlay floats above the whole app and the app underneath stays interactive (even when the player is not full-screen).
- [ ] **4.2** `StreamProbe.ios.kt` `show(...)` / `hide()`: create/tear down the overlay window; lifecycle-safe. *Exit:* show/hide is leak-free.
- [ ] **4.3** iOS renderer: programmatic `UIView`/`UIStackView`/`UITableView` (no XIB/storyboard) consuming `presenter.viewState` (via SKIE async-sequence, or `.value`/manual collect pre-SKIE); maps `OverlayRow → UITableViewCell`.
- [ ] **4.4** Drag via `UIPanGestureRecognizer`; collapse/chip/error-indicator all driven by the **same** `OverlayPresenter` — taps forward to `presenter.onChipSelected(...)` / `onCollapseToggled()`.

**Exit gate:** the iOS demo app shows the UIKit overlay rendering live AVPlayer diagnostics; Android and iOS render the **same** `OverlayPresenter` state (visual parallel verification).

### Phase 5 — Feature completion + distribution (incl. deferred FairPlay DRM)

- [ ] **5.1** **FairPlay DRM (deferred — D14):** `AVContentKeySession` delegate (FairPlay) → `addDrmSessionEvent` + `updateDrmState(FAIRPLAY)`; latency = request→response. Gated on license-server + cert availability; may slip to a later milestone. A thin Swift shim is allowed if the delegate-heavy interop is hard (D9).
- [ ] **5.2** Remaining-signal polish + graceful degradation: stalls, an "aggregate" badge for roll-up segments (iOS segment cardinality differs from Android — risk #5).
- [ ] **5.3** Language display-name `expect/actual` (D7): `expect fun displayLanguage(tag)` — Android `Locale`, iOS `NSLocale` — replacing the raw BCP-47 fallback.
- [ ] **5.4** XCFramework + checked-in `Package.swift` (SPM binary target); `embedAndSignAppleFrameworkForXcode` for local iteration.
- [ ] **5.5** SKIE (Touchlab): bridge `StateFlow`/`Flow` → Swift async-sequence and sealed classes → Swift enums (`OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent`/`ErrorDetail`).
- [ ] **5.6** iOS demo app (`iosApp/`, separate Xcode project, SwiftUI): plays HLS/FairPlay, attaches `StreamProbe`, shows the UIKit overlay. Outside the Gradle modules.

**Exit gate:** XCFramework consumable via SPM; the iOS demo app exercises full diagnostics; FairPlay validated when license infrastructure is available.

---

## Phase Dependency Graph

```
Phase 0 ── KMP plugin (androidTarget only)
   │
   ▼
Phase 1 ── Pure core → commonMain
   │
   ├──────────────────────┬──────────────────────
   ▼                      ▼
Phase 2                 Phase 3                    ◄── parallelizable after Phase 1
OverlayPresenter        iOS targets + headless         (Phase 3.3 PoC is the earliest
(Android-side leverage) AVPlayerProbe                   feasibility proof — do it first)
   │                      │
   └──────────┬───────────┘
              ▼
           Phase 4 ── iOS UIKit overlay (separate UIWindow)
              │
              ▼
           Phase 5 ── Feature completion + distribution
                      (incl. deferred FairPlay DRM)
```

| Phase | Depends on | Enables | May run in parallel with |
|---|---|---|---|
| 0 | — | everything | — |
| 1 | 0 | 2, 3 | — |
| 2 | 1 | 4 | **3** |
| 3 | 1 | 4; 5 (DRM, distribution) | **2** |
| 4 | 2, 3 | 5 | — |
| 5 | 4 (demo); 3 (DRM/dist); 1 (lang) | — | sub-tasks 5.1–5.6 largely independent |

**Critical path:** `0 → 1 → 2 → 4 → 5`, with Phase 3 branching off after Phase 1 and rejoining at Phase 4.

**Key parallelization insight:** Phase 3 (the iOS feasibility PoC — the entire motivation of this effort) depends only on Phase 1, **not** on Phase 2. After Phase 1 lands, the work forks: one track does Phase 2 (Android-side leverage, byte-for-byte behavior-preserving), the other does Phase 3 (iOS feasibility proof). They converge at Phase 4. So feasibility can be proven early, in parallel with — not behind — the highest-effort UI refactor.

---

## expect/actual Boundary (prefer to *eliminate* expect/actual)

| Obstacle | Resolution | expect/actual? |
|---|---|---|
| `DrmSessionEvent` AtomicLong id counter | Move id generation into `SessionStore.addDrmSessionEvent()` (single writer, already serialized) | **No** |
| `NetworkTimingRegistry` `synchronized` | `kotlinx.atomicfu` reentrant lock in common (callers are non-suspend, I/O thread) | **No** |
| `SimpleDateFormat`/`Date`/`ThreadLocal` | `kotlinx-datetime` `LocalDateTime.Format { }` DSL (`HH:mm:ss.SSS`); ThreadLocal disappears entirely | **No (eliminated)** |
| `Locale.forLanguageTag().displayLanguage` | The one genuine divergence. Raw-tag fallback on iOS for now; Phase 5 adds `expect fun displayLanguage(tag)` (Android `Locale` / iOS `NSLocale`) | **Yes, but deferred** |
| `@VisibleForTesting` | Drop (internal + commonTest are the same module) | **No** |
| `Dispatchers.Main` | Exists on both platforms; the presenter emits a dispatcher-agnostic `StateFlow`, renderers collect on Main | **No** |

New common dependencies: `kotlinx-datetime`, `kotlinx.atomicfu` (coroutines-core is already transitively present). `kotlinx-serialization-json` is already in the catalog (unused) — ready for future session JSON export.

---

## iOS Player Adapter — `AVPlayerProbe` mapping

`AVPlayerProbe` is the iOS analogue of `PlayerInterceptor`: it observes AVFoundation and writes to the **unchanged** `SessionStore.addX()` API using the **existing** models. It invents no new model; it degrades gracefully via nullable fields + `isEstimated`.

| AVFoundation source | SDK write → model | Note / accuracy |
|---|---|---|
| `AVAssetVariant` (iOS 15+): peak/avg bitrate, `presentationSize`, codecs | `updateTrackList` → `VariantInfo` | **PoC.** id/frameRate null/derived; isSelected from access log |
| `AVMediaSelectionGroup` (audio/legible) | `AudioTrackInfo`/`SubtitleTrackInfo` | channelCount/sampleRate often 0; CC vs SIDECAR from `mediaCharacteristic` |
| `AVPlayerItemAccessLogEvent.indicatedBitrate/switchBitrate` (KVO) | `addTrackSwitchEvent(VideoSwitch)` + `updateActiveTrack` | bitrate delta → switch; reason `ADAPTIVE`/`UNKNOWN` |
| accessLog: bytesTransferred, observedBitrate, uri, durationWatched, serverAddress | `addSegmentMetric` | **Coarse/roll-up, not per-segment.** networkTiming `isEstimated = true` or null |
| serverAddress (no header access) | `CdnHeaderInfo(cacheStatus = UNKNOWN, cdnProvider = null)` | `CdnHeaderParser` runs with an empty map; CDN detection degrades to UNKNOWN on iOS |
| `AVPlayerItemErrorLogEvent` | `addPlaybackError(LOAD_ERROR, status, domain)` | Maps cleanly |
| accessLog `numberOfDroppedVideoFrames` | `addPlaybackError(DROPPED_FRAMES)` | Reuses the existing 5 s burst dedup |
| `AVContentKeySession` delegate (FairPlay) | `addDrmSessionEvent` + `updateDrmState(FAIRPLAY)` | latency = request→response; **`DrmScheme.FAIRPLAY` added** (the single model addition). **Deferred to Phase 5 (D14).** |

**Android signals with no iOS counterpart (graceful degrade):** per-segment TTFB + the redirect-key invariant (no DataSource hook → `NetworkTiming` null/estimated), CDN response headers (UNKNOWN), authoritative video-switch + reason (heuristic from bitrate delta), true per-segment cardinality (roll-up → fewer/coarser `SegmentMetric`s). **Adapter language:** because the effort is learning-focused, the adapter is written in `iosMain` with **Kotlin/Native cinterop** (symmetry with `PlayerInterceptor` is an instructive artifact); only if the delegate-heavy `AVContentKeySession` proves hard does a thin Swift shim call into the Kotlin `SessionStore`.

---

## UI Strategy — `OverlayPresenter`, rendered by both platforms

Platform-independent logic to move from `OverlayManager` (current 473 lines) into common: `ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }` + `previousViewMode` + collapse; the auto-fallback to TRACKS when the DRM list empties (`observeDrm`); error-indicator text/visibility + header counter (`observePlaybackErrors`); rendition-list assembly (`SectionHeader + items → RenditionListItem`); all `OverlayFormatters`/`DrmFormatters` calls → `OverlayViewState` carries **pre-formatted strings, not raw models**.

```
OverlayViewState(mode, isCollapsed, activeTrackText, activeAudioText, activeSubtitleText,
  latestSegmentText, cdnStatusText, listRows: List<OverlayRow>, drmVisible, drmStatusText,
  errorIndicator: ErrorIndicatorState?, chipRowVisible, errorsHeaderVisible, errorsTitle)
```

**Android `OverlayManager` refactor:** `show/hide`, `addContentView`, the lifecycle observer, `MotionEvent` drag, and size/orientation rebuild **stay** (genuinely Android). The 10 `collect` calls collapse into a **single** `presenter.viewState.collect { render(it) }`. `render` pushes strings into the existing TextViews, toggles visibility, and `submitList`s. The share button still builds an Android `Intent`, but its text comes from `formatErrorsForExport`, which is already pure/common. **iOS renderer:** Swift/UIKit collects the same `presenter.viewState` (cleanly, via SKIE) and maps `OverlayRow → UITableViewCell`. Phase 2's payoff: the iOS overlay reduces to "render this ViewState, forward these taps."

---

## Distribution

- **Android AAR:** coordinates unchanged (`io.github.oguzhaneksi:streamprobe`). vanniktech 0.36.0 supports KMP — with `kotlin("multiplatform")` + androidTarget it wires up the `release` AAR publication + klibs + the root metadata module automatically. The existing POM / `publishToMavenCentral()` / `signAllPublications()` keep working.
- **iOS XCFramework — SPM (recommended).** Apple-native, no Ruby/CocoaPods toolchain; the Kotlin `XCFramework {}` task + a checked-in `Package.swift` binary target. `embedAndSignAppleFrameworkForXcode` for local iteration.
- **SKIE (Touchlab) — add in Phase 4.** Bridges `StateFlow`/`Flow` to Swift async-sequences, sealed classes to Swift enums (OverlayRow/TrackSwitchEvent/DrmSessionEvent/ErrorDetail), and suspend to async. Not needed for the Phase 3 headless PoC (`.value` suffices).
- **iOS demo app:** separate from Android `app/`, its own Xcode project (`iosApp/`), SwiftUI, plays HLS/FairPlay + attaches `StreamProbe` + shows the UIKit overlay. Outside the Gradle modules.

---

## Risks & Resolved Decisions

**Risks:**
1. Media3 `@UnstableApi` is in every adapter — keeping it in `androidMain` ensures it never leaks to common; the common `StreamProbe` facade must **not** declare `ExoPlayer`/`DataSource.Factory` signatures (Android actual only).
2. The redirect-key TTFB invariant has **no** iOS analogue — do not fake it; leave it estimated/null.
3. The concurrent-DRM single-scheme limitation also exists on iOS (a shared, known limit — not a regression).
4. **Resolved by D13** — `AVAssetVariant` requires iOS 15+; with the deployment base set to 15 there is no 13/14 degrade-variant path.
5. Access-log roll-up → iOS segment cardinality differs (a later "aggregate" badge — sub-task 5.2).
6. **Resolved by D15** — iOS overlay window management is the finickiest part; the choice is made: a **separate `UIWindow`** at a high `windowLevel` (true always-on-top, even when the player is not full-screen). The residual difficulty (hit-testing/passthrough so the app stays interactive) is concrete Phase 4 work (sub-task 4.1), not an open question.
7. Kotlin 2.3.20 + AGP 9.1.0 is a very new combination — Phase 0 exists precisely to flush this early, with zero behavior change.

**Resolved decisions (formerly open; now fixed — see D13–D15):**
- **(a) iOS deployment base = 15.** Clean `AVAssetVariant`; no degrade-variant fallback.
- **(b) FairPlay DRM = deferred.** Stays in Phase 5, gated on license-server + cert availability; explicitly not part of the first iOS feature-complete pass.
- **(c) Overlay = separate root-level `UIWindow`.** Production players may not be full-screen, so confining the overlay to a small player-sized layer is wrong; a root-level window keeps it always-on-top across the whole app.

---

## Verification

- **Phase 0:** `./gradlew :sdk:assembleRelease` produces the same AAR; `./gradlew :sdk:test :sdk:lint :sdk:ktlintCheck :sdk:detekt` green; `./gradlew :app:assembleDebug` compiles unchanged. (Zero-behavior-change regression gate.)
- **Phase 1:** pure tests run as commonTest (`./gradlew :sdk:testDebugUnitTest` or KMP `allTests`); any JVM-API use in common is a **compile error** (portability proof). AAR behavior identical.
- **Phase 2:** the new `OverlayPresenterTest` (commonTest) verifies the ViewMode/collapse/DRM-fallback/error-counter contract; in the Android demo app the overlay's 5 tabs + drag + collapse reproduce the old behavior exactly (manual + existing Robolectric tests).
- **Phase 3 (PoC feasibility gate):** `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` builds the framework; a Swift harness opens HLS via `AVPlayer` in the simulator, attaches the probe, and dumps the real `VariantInfo` list from `sessionStore.trackList.value` to the console → **chain proven**.
- **Phase 4+:** the iOS demo app shows the UIKit overlay rendering live AVPlayer diagnostics; the Android and iOS overlays render the same `OverlayPresenter` state (parallel visual verification).

**First concrete step:** implement **only Phase 0** — KMP plugin + androidTarget, no code moved, AAR byte-identical. It eliminates all toolchain risk with zero behavior change and safely unlocks everything that follows.
