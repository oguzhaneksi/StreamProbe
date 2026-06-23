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
| **D14** | **FairPlay DRM timing** | **Deferred** to **Phase 6** (re-scoped 2026-06-18), gated on license-server + cert availability; **not** in the iOS feature-complete pass |
| **D15** | **iOS overlay window** | **Separate root-level `UIWindow`** at a high `windowLevel` — production players may not be full-screen, so confining the overlay to a small player layer is wrong; residual hit-test passthrough handled in Phase 4 |
| **D16** | **iOS demo app UI** | **SwiftUI** (re-scoped 2026-06-18). Replaces the minimal UIKit demo from Phase 4 with a comprehensive SwiftUI app at Android parity: stream-selection list → fullscreen player (custom controls) → Settings. **HLS streams only** (no DASH/MP4/DRM). The UIKit overlay window is unchanged — the SwiftUI app coexists with it. |
| **D17** | **iOS demo test strategy** | **Unit (XCTest) + UI (XCUITest).** AVPlayer is abstracted behind a protocol so the player view-model is unit-testable headlessly; XCUITest covers the navigation flow (launch → select stream → player → exit). "Completely testable" is the Phase 5 acceptance bar. |
| **D18** | **iOS demo Settings** | **Overlay + playback prefs** (show/hide overlay, auto-play, loop). **No** port of Android's "inject load errors" toggle — that needs a Media3 `DataSource` hook AVPlayer lacks; the `AVAssetResourceLoaderDelegate` injection path stays out of scope. **No track-selection sheet** (trimmed for simplicity). |

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

- [x] **1.1** Add `kotlinx-datetime` + `kotlinx.atomicfu` to the version catalog and `commonMain` deps. *Actual:* `kotlinx-datetime 0.7.1`, `atomicfu 0.29.0` (resolve cleanly against Kotlin 2.3.20; `kotlin.time.Instant` is stable — no `@OptIn` needed). `commonMain` also needs `kotlinx-coroutines-core` explicitly (the android set only had `-android`); `commonTest` uses `kotlin("test")` + multiplatform `kotlinx-coroutines-test`.
- [x] **1.2** Move the 17 models + enums to `commonMain/.../model/` verbatim (same package). Add `DrmScheme.FAIRPLAY`. *Actual:* all models were already pure (KDoc `Tracks`/`Format` refs are comments, not imports). `FAIRPLAY` forced `FAIRPLAY` branches in `DrmFormatters.formatDrmScheme`/`formatDrmSchemeBadge` → "FairPlay"/"FP".
- [x] **1.3** Fix `DrmSessionEvent` id-gen (D4): remove the `AtomicLong` from the model; pass `id` as a constructor param assigned by the store. *Actual:* `id` defaults to `UNASSIGNED_ID = 0L`; added `fun withId(id): DrmSessionEvent` (each subtype `copy(id=…)`) so the store stamps a stable id. All construction sites omit `id` (unchanged); `DrmTimelineAdapter` only reads it.
- [x] **1.4** Move `SessionStore.kt` to commonMain; assign DRM event ids inside `addDrmSessionEvent()` (single writer, already serialized); drop `@VisibleForTesting` (D8). *Actual:* `private var drmEventIdCounter`; id computed **before** `update {}` so a CAS retry never double-increments. `@VisibleForTesting` constants kept `internal const`.
- [x] **1.5** Move `NetworkTimingRegistry.kt`; replace `synchronized` with a `kotlinx.atomicfu` lock (D5); keep the `"uri|position"` key + FIFO eviction at `MAX_ENTRIES = 128`. *Actual:* `SynchronizedObject` + `kotlinx.atomicfu.locks.synchronized`. The common `LinkedHashMap` has **no `removeEldestEntry` hook** (JVM-only) — eviction of the eldest key is done manually after each insert (insertion order preserved).
- [x] **1.6** Move `CdnHeaderParser.kt`; replace `Locale.ROOT.lowercase()` with Kotlin's locale-independent `lowercase()`. *Actual:* also `uppercase(Locale.ROOT)` → `uppercase()`.
- [x] **1.7** Split `DrmSchemeDetector`: pure half (`mapUuidToScheme`/`mapDrmState`) → commonMain (`DrmSchemeDetectorCommon`); `detectScheme(eventTime.timeline)` stays in androidMain. *Actual:* the "pure half" isn't pure as written (`java.util.UUID` + `C.*_UUID` + `DrmSession.STATE_*`). Common version is framework-neutral: `mapUuidToScheme(uuidString)` against canonical UUID strings, `mapDrmState(Int)` against locally-defined ints mirroring Media3. Android `DrmSchemeDetector` keeps `UUID`/`@DrmSession.State` overloads that delegate (`uuid.toString()`), so the Robolectric `DrmSchemeDetectorTest` validates the real constants still map correctly.
- [x] **1.8** Move `OverlayFormatters` + `DrmFormatters` to commonMain; replace `SimpleDateFormat`/`ThreadLocal` with `kotlinx-datetime` `LocalDateTime.Format { }` producing `HH:mm:ss.SSS` (D6). *Actual:* kept package `internal.overlay` (NOT `presenter/`) to honor the exit-gate "call sites unchanged"; the `presenter/` reorg belongs to Phase 2. Two extra hurdles surfaced: (a) `String.format(Locale.ROOT,…)` is JVM-only → replaced with a top-level `oneDecimal()` HALF_UP helper (positive inputs match `%.1f`); (b) `Locale.forLanguageTag().displayLanguage` is JVM-only → introduced the **D7 `expect fun displayLanguage(tag)`** one phase early (Android `actual` only, since it's the only target), keeping Android output byte-identical. Timestamp conversion uses `TimeZone.currentSystemDefault()` to match the old `SimpleDateFormat` behavior.
- [x] **1.9** Move the pure tests to commonTest (SessionStore, NetworkTimingRegistry, CdnHeaderParser, DRM mapping, Formatters). *Actual:* converted JUnit → `kotlin.test` (needed for native targets in Phase 3): `@Before`→`@BeforeTest`, and `assertTrue("msg", cond)` arg-order swapped to `assertTrue(cond, "msg")`. New `DrmSchemeDetectorCommonTest` covers the pure mapping; `DrmSessionStoreTest` updated for store-assigned ids. The media3/Robolectric `DrmSchemeDetectorTest` stays in androidHostTest. commonTest runs under `testAndroidHostTest` (457 tests green).

> **Detekt note (Phase 1):** `commonMain` is analysed by a **new task `:sdk:detektMetadataMain`** — add it to the CI gate. Moving `OverlayFormatters` to common put it at the `TooManyFunctions` object-threshold (20): kept it at 19 by moving the private `oneDecimal` helper to a top-level function (the *file*-level threshold tolerates 20; only the *object* rule fired).

**Exit gate:** AAR builds; pure tests run as commonTest on JVM (457 green); `commonMain` compiles with **zero `java.`/`javax.`/`android.`/`androidx.` imports** — proving it is genuinely portable. Call sites unchanged (same packages). Full gate green: `:sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`.

### Phase 2 — Extract OverlayPresenter; reduce OverlayManager to a renderer ✅ COMPLETE

Move all platform-independent logic out of `OverlayManager` (currently 473 lines) into a common presenter that emits a single `StateFlow<OverlayViewState>`.

- [x] **2.1** Define `OverlayViewState` + the `OverlayRow` sealed interface + `ErrorIndicatorState` in `commonMain/presenter/`. *Actual:* `OverlayViewState` grouped into `OverlayStatsState` (7) + `OverlayListsState` (5) sub-structs to stay under `detektMetadataMain`'s `LongParameterList` constructorThreshold=8; top-level `OverlayViewState` is 8 params.
- [x] **2.2** Create the `OverlayPresenter` skeleton: consumes `SessionStore` flows, exposes a dispatcher-agnostic `StateFlow<OverlayViewState>`. *Actual:* `start(scope)` folds all `SessionStore` flows into one `StateFlow<OverlayViewState>`; mutable UI-state vars confined to the single dispatcher passed at construction (Main on Android, test dispatcher in commonTest).
- [x] **2.3** Move the `ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }` state machine + `previousViewMode` + collapse into the presenter; expose `onChipSelected(...)` / `onCollapseToggled()`. Write `OverlayPresenterTest` (commonTest) for mode transitions + collapse.
- [x] **2.4** Move the DRM auto-fallback (`observeDrm`: DRM list empties → return to TRACKS) into the presenter. Test the transition.
- [x] **2.5** Move the error-indicator text/visibility + header counter (`observePlaybackErrors`) into the presenter. Test the counter contract. Also exposes `onErrorIndicatorTapped`, `onBackPressed`, `onClearErrorsClicked`.
- [x] **2.6** Move rendition-list assembly (`SectionHeader + items → RenditionListItem`/`OverlayRow`) and all formatter calls into the presenter. *Actual deviation:* `OverlayRow` carries **raw track models, not pre-formatted strings** — `SubtitleTrackInfo.isSameRenditionAs` DiffUtil identity is non-transitive (nullable-label rule) and can't reduce to a string key. Header/stat fields ARE pre-formatted; per-row Tracks/timeline pre-formatting deferred to Phase 4.
- [x] **2.7** Refactor Android `OverlayManager`: replaced 10 `collect` calls with a single `presenter.viewState.collect { render(it) }`. One `OverlayPresenter` reused across `hide()`/`show()` + orientation rebuilds (created lazily, never nulled) so `ViewMode` survives rotation. Click handlers call `presenter.onX()` then `render(viewState.value)` immediately (Robolectric tests assert synchronously after `performClick()`). *Actual:* `OverlayManager` trimmed from 473 → 379 lines.

> **commonTest StateFlow pattern:** `backgroundScope.launch { flow.collect {} }` + `advanceUntilIdle()` did NOT propagate reliably. Working pattern: `runTest(dispatcher)` with `StandardTestDispatcher`, `val scope = CoroutineScope(dispatcher)`, `presenter.start(scope)`, `advanceUntilIdle()`, assert, `scope.cancel()`.

**Exit gate (actual):** `OverlayPresenterTest` (11 tests, commonTest) + unchanged `OverlayManagerErrorBehaviorTest` (6 tests). Full gate green: `:sdk:iosSimulatorArm64Test :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`. **Committed:** `feat(kmp): Phase 2 — extract OverlayPresenter, reduce OverlayManager to a renderer` + `refactor(kmp): address Phase 2 code-review findings`.

### Phase 3 — iOS targets + headless AVPlayerProbe (PoC — no UI yet) ✅ COMPLETE

This is the **feasibility proof**. It depends only on Phase 1 (see dependency graph) — it does **not** wait for Phase 2.

- [x] **3.1** Add `iosArm64/iosSimulatorArm64/iosX64` targets; `binaries.framework { baseName = "StreamProbe"; isStatic = true }`; set iOS deployment target **15** (D13). Added `-Xexpect-actual-classes` to `kotlin.compilerOptions` (expect/actual classes are Beta in Kotlin 2.3). *Exit:* `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` produces a framework.
- [x] **3.2** `StreamProbe` promoted to `expect class` (commonMain, member `fun detach()`). `StreamProbe.ios.kt` actual: `attach(AVPlayer)`, headless. Owns `SessionStore` + `AVPlayerProbe`, exposes `internal val sessionStore`. `StreamProbe.android.kt` = `actual class`, existing API unchanged, `detach()` → `actual`.
- [x] **3.3** **PoC milestone — `AVAssetVariant` track/variant listing end-to-end.** `AVMetricMappers.kt`: pure helpers `pickVariantBitrate`, `dimensionOrUnknown`, `frameRateOrUnknown`, `joinCodecs`, `preferredLanguageTag`, `defaultSubtitleKind`; maps `AVAssetVariant`→`VariantInfo`, `AVMediaSelectionOption` audible→`AudioTrackInfo`/legible→`SubtitleTrackInfo`. `AVPlayerProbe` discovers tracks via `AVURLAsset.loadValuesAsynchronouslyForKeys(["variants"])` — **not** KVO/playback-observer (doesn't progress headless). Checks `statusOfValueForKey` + verifies `currentItem?.asset === asset` (stale-closure guard).
- [x] **3.4** Access-log segment metrics via `AVPlayerItemNewAccessLogEntryNotification` (scoped to `player.currentItem`). `AVAccessLogMappers.kt`: `segmentThroughput` (prefers `observedBitrate/8`), `accessLogSegmentMetric` (networkTiming null, CDN = UNKNOWN). Entries processed **one notification behind** (finalized only — `dropLast(1)`). `requestTimestampMs` = `event.playbackStartDate?.timeIntervalSince1970`, fallback `nowMs()`. `CdnHeaderParser.parse(emptyMap())` result cached as `private val UNKNOWN_CDN_INFO`.
- [x] **3.5** Access-log bitrate switches: `indicatedBitrate` change across entries → `VideoSwitch` (INITIAL then ADAPTIVE); `ActiveTrackInfo` carries bitrate only (res/codecs `-1`/null). `store.activeTrack.value` is the single source of truth for previous-track (no `lastActiveTrack` field). `nowMs()` uses `CACurrentMediaTime()` + epoch offset computed at `attach()` (monotonic).
- [x] **3.6** Error + dropped-frames via `AVPlayerItemNewErrorLogEntryNotification`. `droppedFramesError` = per-entry `numberOfDroppedVideoFrames ≥ 3`. `IosConstants.kt`: `internal const val MILLIS_PER_SECOND = 1000L` (eliminates duplication). `store.clear()` called in `StreamProbe.attach()` before `probe.attach()`.

> **K/N AVFoundation binding gotchas:** Objective-C *member* properties must NOT be imported (use directly: `peakBitRate`, `averageBitRate`, `URL`, `absoluteString`, etc.). Category/extension properties MUST be imported: `extendedLanguageTag`, `nominalFrameRate`, `variants`, `loadValuesAsynchronouslyForKeys`. `variants` is on `AVURLAsset` — cast `item.asset as? AVURLAsset`. `AVMediaCharacteristic*` constants are `String?` (null-guard before use). `CValue<CGSize>` → `size.useContents { width; height }`.

> **ktlint lints `iosMain`/`iosTest`:** `no-consecutive-comments` — KDoc may NOT be followed by a `//` comment or another KDoc; use plain `/* */` for file-level overviews. `class-signature` — single primary-ctor param must be multiline.

> **Portability gotchas surfaced by the native target:** Native forbids `()` in backtick test names — rewrote two `OverlayFormattingTest` names. `displayLanguage` iOS actual added (`LanguageNames.ios.kt`; raw BCP-47 fallback). Two commonTest formatter tests hardcoded JVM `Locale` output → rewritten to use `displayLanguage` (portable); `LanguageNamesTest` (androidHostTest) pins the JVM "en"→"English" contract.

> **⚠️ Live PoC leg is environment-blocked (not a code defect):** iOS Simulator cannot complete TLS in this sandbox (`status=2`; CoreSimulator network daemon isolation). `AVPlayerProbePocTest` skips the live leg with a loud log when the stream can't load; pure mapper tests (AVMetricMappersTest + AVAccessLogMappersTest) give hermetic coverage.

> FairPlay DRM is **deferred** (D14) — it is **not** in Phase 3.

**Exit gate (actual):** Full gate green — iOS 174 tests, Android host 240 tests, 0 failures. Counts: Android host = commonTest 154 + androidHostTest 86; iOS = commonTest 154 + iosTest 20. **Committed:** `feat(kmp): complete KMP migration phase 3 — iOS targets + headless AVPlayerProbe` + `fix(ios): apply code-review fixes to AVPlayerProbe and supporting mappers` (merged into `feature/kmp_migration` via worktree `kmp-phase3-ios`).

### Phase 4 — Programmatic UIKit overlay on iOS (separate `UIWindow`) ✅ COMPLETE

- [x] **4.1** Overlay window: `StreamProbeOverlayWindow` — a `UIWindow` subclass at `windowLevel = .alert + 1` (D15). `hitTest` overridden: touches that land on the transparent window background (`self` or `rootViewController?.view`) return `nil`, falling through to the main window. *Actual:* 14-line `StreamProbeOverlayWindow.swift`. Created by the **host app** (`SceneDelegate`) not the SDK, which keeps UIKit fully out of the Kotlin framework. `overlayWindow` held strongly in `SceneDelegate`.
- [x] **4.2** `StreamProbe.ios.kt` `show()` / `hide()`: control the **presenter** scope, not the UIKit window. `show()` creates a `CoroutineScope(Dispatchers.Main + SupervisorJob())` and calls `overlayPresenter.start(scope)`; `hide()` cancels it (live updates freeze, overlay stays visible). UIKit window lifecycle is the host app's responsibility. *Actual deviation from plan:* the SDK does not create or destroy any UIKit objects; the separation is cleaner and avoids a UIKit dependency in the Kotlin module.
- [x] **4.3** iOS renderer (`iosApp/iosApp/Overlay/`): all programmatic UIKit (no XIB/storyboard). `OverlayPanelView` — `UIStackView` with header row, `ChipBarView`, `StatsView`, `UITableView` (dark semi-transparent `UIColor.black.withAlphaComponent(0.82)` background, `cornerRadius=12`). `OverlayHostViewController` — owns `Task { @MainActor for await state in presenter.viewState { render(state) } }` SKIE async-sequence loop; drives `tableHeightConstraint.constant` + `layoutIfNeeded()` for animated collapse. `OverlayTableDataSource` — `UITableViewDataSource`/`UITableViewDelegate` using `onEnum(of:)` for `OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent` exhaustive dispatch. `StatsView` — renders pre-formatted `OverlayStatsState` strings.
- [x] **4.4** Drag via `UIPanGestureRecognizer` on `OverlayPanelView` (translates `center` by pan delta, zero-resets translation). Collapse button, error-indicator button, and chip callbacks all forward to `presenter.on*(...)`. `ChipBarView` — horizontally-scrollable chip bar consuming `ViewMode` as a SKIE Swift enum; `onChipSelected: ((ViewMode) -> Void)?` closure.

> **SKIE 0.10.11 pulled into Phase 4 (was planned for 5.5):** required to bridge `StateFlow<OverlayViewState>` → Swift `AsyncStream` and expose `OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent` sealed hierarchies via `onEnum(of:)`. Added `co.touchlab.skie` plugin to `sdk/build.gradle.kts` (version `0.10.11`). SKIE generates typed Swift wrappers in `sdk/build/skie/`.

> **Public surface change (Phase 4 SDK, commit 937eef4):** `OverlayPresenter` promoted to `public class` with `internal constructor`; `ViewMode`, `OverlayRow` and its subtypes, `ErrorIndicatorState`, `OverlayStatsState`, `OverlayListsState`, `OverlayViewState` all promoted to `public`. `start(scope)` remains `internal` — only `StreamProbe.ios.kt` calls it. `overlayPresenter: OverlayPresenter` added as `public val` to `StreamProbe.ios.kt`.

> **AutoLayout fix (commit 447f34a):** height constraints on `chipWrapper`, `statsWrapper`, and `tableHeightConstraint` lowered to `.defaultHigh` (999) so `UIStackView`'s internal zero-height override wins cleanly when subviews are hidden. Collapse animation drives `tableHeightConstraint.constant` + `layoutIfNeeded()` rather than `frame.size.height` to avoid the two-authority constraint conflict. `tableHeightConstraint` exposed as `internal` so `OverlayHostViewController` can drive it directly.

> **iOS demo app (commit 88426b0):** UIKit via `AppDelegate`/`SceneDelegate`/`PlayerViewController` — not SwiftUI (SwiftUI deferred to Phase 5; the overlay is UIKit, so UIKit makes a simpler demo). `SceneDelegate` creates both windows; `PlayerViewController` owns `StreamProbe_()` (SKIE name-mangling of `StreamProbe`), calls `probe.attach(player:)` + `probe.show()`. Generated via XcodeGen (`iosApp/project.yml`, deployment target iOS 15). References `sdk/build/XCFrameworks/debug/StreamProbe.xcframework`. `CFBundleExecutable` added to `Info.plist` in commit 60ad25e.

**Exit gate (actual):** iOS demo app (`iosApp/`) builds and runs; `OverlayHostViewController` renders live AVPlayer diagnostics via `presenter.viewState` SKIE async-sequence; Android and iOS render the same `OverlayPresenter` `ViewState` (visual parallel verification confirmed). **Committed:** `feat(kmp/ios): Phase 4 SDK — SKIE, public overlay types, show/hide presenter API` + `feat(ios): Phase 4 — Swift/UIKit overlay window, panel renderer, iosApp demo` + `fix(ios): resolve AutoLayout constraint conflict on panel collapse` + `feat(ios): add CFBundleExecutable key to Info.plist for dynamic executable name`.

> **Phase 4 follow-up — iOS overlay Android parity (committed after initial Phase 4):** The initial Phase 4 overlay used generic `textLabel` cells. A subsequent redesign (`docs/superpowers/plans/2026-06-16-ios-overlay-android-parity.md`) brought the iOS overlay to pixel-for-pixel parity with Android. All changes are host-app side (`iosApp/iosApp/Overlay/`); no SDK changes.
>
> **New files added:**
> - `OverlayTheme.swift` — all color tokens (`panelBg #E6101024`, `headerBg #331A1A3A`, `accent #66B2FF`, etc.) + `cacheDot(status:)` / `errorCategoryDot(_:)` / `drmEventDot(_:)` factories; `UIColor(argbHex:)` extension parses Android ARGB hex.
> - `OverlayFormattersSwift.swift` — Swift port of `OverlayFormatters.kt` + `DrmFormatters.kt` (those are `internal` Kotlin, invisible to Swift): all formatting helpers for the five list tabs.
> - `HeaderView.swift` — 44pt header bar (title label with kern 0.04em, error pill on `#FF453A`, collapse button that rotates 180°; header background is `#331A1A3A` with top-corners-only `cornerRadius = 14` via a `CALayer` sublayer).
> - `ErrorsHeaderView.swift` — errors-mode header replacing the chip bar: ← Back · Errors (N) · Clear · ↗ Share.
> - `RenditionCell.swift` — `RenditionSectionHeaderCell` + `RenditionItemCell` (8pt dot + two-line: resolution/bitrate top, codecs bottom).
> - `SegmentCell.swift` — `#N · DL: Xms · cacheDot` + secondary (size · throughput · TTFB).
> - `SwitchCell.swift` — `#N · VID/AUD/SUB badge · switch text` + secondary (buf · reason · +timestamp).
> - `ErrorCell.swift` — expandable: summary line always visible; detail block toggled by tapping (expand state keyed by `timestampMs` in `OverlayTableDataSource.expandedTimestamps: Set<Int64>`).
> - `DrmCell.swift` — `#N · dot · scheme badge · event label · latency (KeysLoaded only) · +timestamp`.
>
> **Files fully rewritten:** `StatsView.swift` (section-label + value-row hierarchy matching Android spacing), `ChipBarView.swift` (accent style: fill `#66B2FF` checked / border unchecked; DRM chip hidden when `!drmVisible`; title-case labels), `OverlayPanelView.swift` (portrait = vertical stack; landscape = horizontal split with `fillEqually` columns; `refreshTableHeight()` sizes the table to `min(contentSize, cap)`), `OverlayTableDataSource.swift` (registers 6 cell types; dispatches on `state.mode`; auto-scrolls to newest when already at bottom), `OverlayHostViewController.swift` (drag clamped to safe area via `viewSafeAreaInsetsDidChange`; sizing driven by `viewDidLayoutSubviews` → `applySizing()` rather than `UIDevice.orientationDidChangeNotification` to catch all geometry changes; panel snaps to top-right corner on orientation change; share via `UIActivityViewController`).
>
> **Xcode project:** converted `Overlay` group to `PBXFileSystemSynchronizedRootGroup` (`build(ios): convert Overlay group to file-system-synchronized group`) so new overlay files are auto-included without editing `project.pbxproj`.
>
> **Key layout decisions:**
> - Table height constraint priority `.required`, constant set to `min(contentSize.height, maxTableHeight)` and refreshed after every `reloadData` (`refreshTableHeight()`). This gives the table a definite height (fixes empty list in portrait).
> - Portrait max: **180pt**; landscape max: `(bounds.height × 0.55).clamped(200…360)`.
> - Panel width: portrait `min(screenWidth − 32, 310)`; landscape `min(screenWidth − 32, 540)`.
> - Drag attaches to `HeaderView` only (not the full panel), clamped to `view.safeAreaInsets` on every pan event and re-clamped on `viewSafeAreaInsetsDidChange`.
> - Landscape columns use `alignment = .top` so chip bar aligns with the first stat row.
>
> **Commits for this follow-up:** `docs: add iOS overlay design spec` + `docs: add iOS overlay Android-parity implementation plan` + per-task commits (OverlayTheme → OverlayFormattersSwift → HeaderView → StatsView → ChipBarView → ErrorsHeaderView → RenditionCell → SegmentCell → SwitchCell → ErrorCell → DrmCell → OverlayTableDataSource → OverlayPanelView + OverlayHostViewController) + several bug-fix commits (`fix(ios-overlay): scrollable non-wrapping chip row`, `fix(ios-overlay): give table a definite content-based height`, `fix(ios-overlay): top-align landscape columns`, `fix(ios-overlay): position panel within safe area after insets resolve`).

### Phase 5 — Comprehensive SwiftUI demo app + iOS testability ✅

> **Re-scoped 2026-06-18 (D16–D18):** Phase 5 was "Feature completion + distribution (incl. FairPlay)". FairPlay, SPM distribution, the language `expect/actual`, and remaining-signal polish all moved to the **new Phase 6**. Phase 5 is now laser-focused on a **comprehensive SwiftUI demo at Android parity** and making the iOS app **completely testable**. The existing minimal UIKit demo (`AppDelegate`/`SceneDelegate`/`PlayerViewController` from Phase 4) is replaced by a SwiftUI app; the UIKit **overlay** window is unchanged and the SwiftUI app coexists with it.

Scope (D16): stream-selection list (**HLS only**) → fullscreen player (custom controls: seek ±10s, play/pause, buffered seekbar, position/duration, exit) → Settings (overlay + playback prefs, D18). No track-selection sheet, no DASH/MP4/DRM streams.

- [x] **5.1** **App shell + overlay coexistence (D16):** structure the SwiftUI app so it still creates the SDK's separate root-level overlay `UIWindow` (`StreamProbeOverlayWindow`, `windowLevel = .alert+1`) and hands it `probe.overlayPresenter`. Resolve the SwiftUI-`App`-lifecycle vs UIKit-`SceneDelegate` question (see implementation plan). *Exit:* app launches, overlay window renders above SwiftUI content.
- [x] **5.2** **Stream-selection screen:** SwiftUI list of curated **HLS** streams (cards: title + HLS badge), parity with Android `StreamSelectionScreen`; a Settings entry point. *Exit:* tapping a stream navigates to the player; tapping Settings opens Settings.
- [x] **5.3** **Player view-model + AVPlayer abstraction (D17):** an `ObservableObject` driving play/pause, position, duration, buffered fraction, buffering state from AVPlayer (periodic time observer + KVO). AVPlayer hidden behind a protocol so the view-model is unit-testable headlessly. `probe.attach(player:)` + `probe.show()` wired here. *Exit:* unit tests drive the view-model with a fake player.
- [x] **5.4** **Fullscreen player screen + custom controls:** SwiftUI player (custom controls, NOT system chrome) — seek ±10s, play/pause, scrubber with buffered progress + drag-to-seek, position/remaining labels, exit button; tap-to-toggle + auto-hide. Parity with Android `PlayerController`. *Exit:* controls drive playback; exit returns to the list and releases the player.
- [x] **5.5** **Settings screen (D18):** SwiftUI Settings with overlay + playback prefs (show/hide overlay → `probe.show()`/`hide()`; auto-play; loop), persisted (`UserDefaults`/`@AppStorage`). *Exit:* toggling overlay visibility hides/shows the SDK overlay live.
- [x] **5.6** **Unit tests (XCTest):** player view-model (with fake AVPlayer), scrub/seek logic, settings persistence, stream-list model. *Exit:* tests green in Xcode + on `xcodebuild test`.
- [x] **5.7** **UI tests (XCUITest):** accessibility identifiers on key views; flow test launch → select stream → player appears → controls visible → exit → back to list; Settings toggle reflected. *Exit:* XCUITest suite green.

**Exit gate:** SwiftUI demo runs at Android parity (stream list → player → settings) and exercises live diagnostics through the unchanged UIKit overlay; the app is completely testable — XCTest unit suite + XCUITest flow suite both green via `xcodebuild test`.

### Phase 6 — Feature completion + distribution (incl. deferred FairPlay DRM)

> **New phase (re-scoped 2026-06-18):** absorbs everything that was in the old Phase 5 except the demo app. Sub-tasks are largely independent; FairPlay (6.1) is gated on external license infrastructure and may slip to a later milestone.

- [ ] **6.1** **FairPlay DRM (deferred — D14):** `AVContentKeySession` delegate (FairPlay) → `addDrmSessionEvent` + `updateDrmState(FAIRPLAY)`; latency = request→response. Gated on license-server + cert availability. A thin Swift shim is allowed if the delegate-heavy interop is hard (D9). Add a DRM (FairPlay) HLS stream to the demo's stream list when this lands.
- [→] **6.2** **Promoted out of the KMP migration → standalone feature milestone M12 (re-scoped 2026-06-23).** What was "remaining-signal polish (stalls + aggregate badge)" grew into a real cross-platform feature, so it now lives as **M12 — QoS Stall Diagnosis** in the root `README.md` roadmap rather than as a migration sub-item. Re-framed as **QoS, not QoE**: a stall is recorded only as a *root-cause classification* (`SEGMENT_LOAD_FAILURE` / `BANDWIDTH_STARVATION` / `THROUGHPUT_HEALTHY` / `UNKNOWN`) derived from existing throughput + error signals, surfaced in the **Errors** view as a `STALL` entry (verdict + evidence), never as a bare "a stall happened" event. Both platforms emit (Android `STATE_BUFFERING`-after-`STATE_READY`, seek-suppressed; iOS `AVPlayerItemPlaybackStalledNotification`) through a single pure classifier (`StallDiagnostics`, commonMain). Still includes the `SegmentMetric.isAggregate` "AGG" badge for iOS roll-up access-log entries (risk #5). Design spec: `docs/superpowers/specs/2026-06-23-phase6.2-qos-stall-diagnosis-design.md`. *Status:* design approved; TDD implementation plan pending.
- [x] **6.3** Language display-name `expect/actual` (D7): `expect fun displayLanguage(tag)` — Android `Locale`, iOS `NSLocale` — replacing the raw BCP-47 fallback. *Actual:* iOS actual extracts the language subtag via `NSLocale.componentsFromLocaleIdentifier(tag)[NSLocaleLanguageCode]` (so `en-US` → `English`, dropping region), localizes against `NSLocale.currentLocale.localizedStringForLanguageCode` for device-locale parity with Android, and falls back to the raw tag when the code is unresolvable (blank → null). New `iosTest/.../LanguageNamesTest` mirrors the androidHostTest one (assumes an English-locale host, as the Android test assumes an English JVM); two stale `OverlayFormattingTest` comments refreshed (those assertions already dogfood `displayLanguage`, so no assertion change). ktlint `multiline-expression-wrapping` forced the `val code` RHS onto its own line. Spec + plan in `docs/superpowers/`. **Committed:** `feat(kmp/ios): Phase 6.3 — NSLocale-backed displayLanguage actual` (`aaf8781`).
- [ ] **6.4** XCFramework + checked-in `Package.swift` (SPM binary target); `embedAndSignAppleFrameworkForXcode` for local iteration. *Note:* the demo app already references the debug XCFramework at `sdk/build/XCFrameworks/debug/StreamProbe.xcframework` via `project.yml`; a formal `Package.swift` for SDK consumers is the remaining distribution step.
- [x] **6.5** ~~SKIE (Touchlab)~~ — **pulled into Phase 4.** SKIE 0.10.11 added in commit 937eef4; bridges `StateFlow<OverlayViewState>` → Swift async-sequence, `OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent`/`ViewMode` → exhaustive Swift enums via `onEnum(of:)`.

**Exit gate:** XCFramework consumable via SPM (`Package.swift`); FairPlay validated when license infrastructure is available; iOS overlay exercises full diagnostics including DRM.

---

## Phase Dependency Graph

```
Phase 0 ✅ KMP plugin (androidTarget only)
   │
   ▼
Phase 1 ✅ Pure core → commonMain
   │
   ├──────────────────────┬──────────────────────
   ▼                      ▼
Phase 2 ✅              Phase 3 ✅               ◄── ran in parallel after Phase 1
OverlayPresenter        iOS targets + headless         (Phase 3.3 PoC is the earliest
(Android-side leverage) AVPlayerProbe                   feasibility proof — do it first)
   │                      │
   └──────────┬───────────┘
              ▼
           Phase 4 ✅ iOS UIKit overlay (separate UIWindow)
              │
              ▼
           Phase 5 ✅ Comprehensive SwiftUI demo + iOS testability
              │
              ▼
           Phase 6 ── Feature completion + distribution
                      (incl. deferred FairPlay DRM)
```

| Phase | Depends on | Enables | May run in parallel with |
|---|---|---|---|
| 0 | — | everything | — |
| 1 | 0 | 2, 3 | — |
| 2 | 1 | 4 | **3** |
| 3 | 1 | 4; 6 (DRM, distribution) | **2** |
| 4 | 2, 3 | 5 | — |
| 5 | 4 (overlay window + presenter API) | — | **6** (sub-tasks independent) |
| 6 | 3 (DRM/dist); 1 (lang) | — | **5**; sub-tasks 6.1–6.5 largely independent |

**Critical path:** `0 → 1 → 2 → 4 → 5`, with Phase 3 branching off after Phase 1 and rejoining at Phase 4. Phase 6 (FairPlay + distribution + lang + polish) depends on Phases 3/1, not on the Phase 5 demo, so it can run in parallel with — or after — Phase 5.

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
- **SKIE (Touchlab) ✅ — added in Phase 4 (commit 937eef4).** SKIE 0.10.11 bridges `StateFlow`/`Flow` → Swift async-sequences, sealed interfaces → Swift exhaustive enums via `onEnum(of:)` (`OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent`/`ViewMode`/`ErrorDetail`). Generated Swift wrappers live in `sdk/build/skie/`.
- **iOS demo app — minimal UIKit demo landed in Phase 4 (`iosApp/`); replaced by a comprehensive SwiftUI app in Phase 5 (D16).** Separate Xcode project (XcodeGen, `project.yml`). Phase 4 shipped a single-screen UIKit demo (`AppDelegate`/`SceneDelegate`/`PlayerViewController`) that plays one HLS stream, attaches `StreamProbe`, and shows the UIKit overlay via `StreamProbeOverlayWindow`. **Phase 5** rebuilds the host app in SwiftUI at Android parity (stream list → fullscreen custom-control player → settings, HLS-only) while keeping the same overlay window; the app is made unit- (XCTest) and UI- (XCUITest) testable (D17).

---

## Risks & Resolved Decisions

**Risks:**
1. Media3 `@UnstableApi` is in every adapter — keeping it in `androidMain` ensures it never leaks to common; the common `StreamProbe` facade must **not** declare `ExoPlayer`/`DataSource.Factory` signatures (Android actual only).
2. The redirect-key TTFB invariant has **no** iOS analogue — do not fake it; leave it estimated/null.
3. The concurrent-DRM single-scheme limitation also exists on iOS (a shared, known limit — not a regression).
4. **Resolved by D13** — `AVAssetVariant` requires iOS 15+; with the deployment base set to 15 there is no 13/14 degrade-variant path.
5. Access-log roll-up → iOS segment cardinality differs (the "AGG" aggregate badge — now part of feature milestone **M12**, formerly sub-task 6.2).
6. **Resolved by D15** — iOS overlay window management is the finickiest part; the choice is made: a **separate `UIWindow`** at a high `windowLevel` (true always-on-top, even when the player is not full-screen). The residual difficulty (hit-testing/passthrough so the app stays interactive) is concrete Phase 4 work (sub-task 4.1), not an open question.
7. Kotlin 2.3.20 + AGP 9.1.0 is a very new combination — Phase 0 exists precisely to flush this early, with zero behavior change.

**Resolved decisions (formerly open; now fixed — see D13–D15):**
- **(a) iOS deployment base = 15.** Clean `AVAssetVariant`; no degrade-variant fallback.
- **(b) FairPlay DRM = deferred.** Moved to **Phase 6** (re-scoped 2026-06-18), gated on license-server + cert availability; explicitly not part of the first iOS feature-complete pass.
- **(c) Overlay = separate root-level `UIWindow`.** Production players may not be full-screen, so confining the overlay to a small player-sized layer is wrong; a root-level window keeps it always-on-top across the whole app.

---

## Verification

- **Phase 0:** `./gradlew :sdk:assembleRelease` produces the same AAR; `./gradlew :sdk:test :sdk:lint :sdk:ktlintCheck :sdk:detekt` green; `./gradlew :app:assembleDebug` compiles unchanged. (Zero-behavior-change regression gate.)
- **Phase 1:** pure tests run as commonTest (`./gradlew :sdk:testDebugUnitTest` or KMP `allTests`); any JVM-API use in common is a **compile error** (portability proof). AAR behavior identical.
- **Phase 2 ✅:** `OverlayPresenterTest` (11 tests, commonTest) locks the ViewMode/collapse/DRM-fallback/error-counter contract; `OverlayManagerErrorBehaviorTest` (6 tests, androidHostTest) unchanged. Full CI gate green. Android overlay behavior preserved byte-for-byte.
- **Phase 3 ✅:** `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` builds the framework. Pure mapper tests (AVMetricMappersTest + AVAccessLogMappersTest, iosTest) give hermetic coverage. `AVPlayerProbePocTest` skips the live leg when simulator TLS is unavailable (environment limitation, not a code defect). Full gate green: `:sdk:iosSimulatorArm64Test :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`.
- **Phase 4 ✅:** iOS demo app (`iosApp/`) builds and runs; `OverlayHostViewController` observes `presenter.viewState` via SKIE Swift async-sequence and renders the same `OverlayPresenter` `ViewState` as Android (visual parallel verification confirmed). SKIE 0.10.11 bridges sealed interfaces to Swift via `onEnum(of:)`. AutoLayout constraint priorities correctly handle animated collapse without ambiguity warnings.
- **Phase 5 ✅:** SwiftUI demo runs at Android parity (stream list → fullscreen player → settings) and renders live diagnostics through the unchanged UIKit overlay; XCTest unit suite (player view-model via a fake AVPlayer, scrub/seek, settings) + XCUITest flow suite both green via `xcodebuild test`. *Actual:* SwiftUI `@main DemoApp` with `UIApplicationDelegateAdaptor` + custom `SceneDelegate` retaining the overlay `UIWindow`; `Info.plist` static `UISceneConfigurations` removed so `SceneDelegate.scene(_:willConnectTo:options:)` installs the overlay window programmatically. HLS-only stream catalog (5 HTTPS streams). `PlayerEngine` protocol hiding `AVPlayer` behind `load/play/pause/seek/teardown` + `currentTimePublisher`; `AVPlayerEngine` and `MockPlayerEngine`. `PlayerViewModel` (`ObservableObject`, periodic time observer + KVO for duration/buffered fraction/buffering state, seek ±10s, scrub-gate) — 9 XCTest unit tests. `SettingsStore` (injectable `UserDefaults` persistence via `@Published`/`didSet`, overlayVisible → overlay-window `isHidden` via `AppDependencies`) — 3 unit tests. `StreamCatalog` — 4 unit tests. `VideoSurfaceView` (`UIViewRepresentable` wrapping `AVPlayerLayer`). `PlayerControlsView` (tap-to-toggle + 3 s auto-hide, seek ±10s, scrubber with buffered progress, position/remaining labels, exit button). `PlayerScreen` (`fullScreenCover`-presented, wires probe). `StreamSelectionScreen` (`NavigationView` + `.navigationViewStyle(.stack)`, `fullScreenCover` → PlayerScreen, gear button → SettingsScreen). `SettingsScreen` (overlay/autoPlay/loop toggles). `AppDependencies` (singleton `StreamProbe_` + `SettingsStore`, `registerOverlayWindow(_:)`). iOS-18 passthrough: `StreamProbeOverlayWindow.hitTest` returns `nil` for background touches so SwiftUI scroll/tap events reach the main window. 3 XCUITest flow tests green (launch → stream list visible; select stream → player appears → exit → list; settings opens → overlay toggle reflected). Loop pref persisted (`UserDefaults`) only; runtime wiring left as an optional refinement.
- **Phase 6:** XCFramework consumable via `Package.swift` (SPM); iOS overlay exercises full diagnostics including FairPlay (when license infrastructure is available).

**First concrete step:** implement **only Phase 0** — KMP plugin + androidTarget, no code moved, AAR byte-identical. It eliminates all toolchain risk with zero behavior change and safely unlocks everything that follows.
