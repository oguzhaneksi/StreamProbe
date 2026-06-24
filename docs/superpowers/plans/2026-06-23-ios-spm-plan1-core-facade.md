# iOS Two-Layer / SPM — Plan 1: Core Swift-Facing Facade

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the new public Core surface (`ProbeCore` holder + `DiagnosticsSink` write interface, plus public mapping helpers) in `iosMain` that the future Swift `StreamProbeIOS` layer will consume — fully additive, leaving Android, iOS, and the demo app all green.

**Architecture:** Per `docs/superpowers/specs/2026-06-23-ios-two-layer-spm-packaging-design.md`, iOS is being split into a Kotlin `StreamProbeCore` binary + a Swift `StreamProbeIOS` source layer. This first plan only introduces the Kotlin-side facade the Swift layer will write through and observe. It changes nothing about the existing `StreamProbe.ios.kt` / `AVPlayerProbe` (those are removed in a later plan), so the current XCFramework and demo keep working.

**Tech Stack:** Kotlin Multiplatform (`iosMain`/`iosTest` source sets), `kotlinx-coroutines-core`, `kotlin.test`, Gradle, SKIE (already configured).

## Global Constraints

- **One module / `internal` is module-wide:** `commonMain`, `iosMain`, `androidMain` compile into one Gradle module; `iosMain` code may call `commonMain`'s `internal` declarations (e.g. `SessionStore`'s write methods, `OverlayPresenter`'s `internal constructor` and `internal fun start`). No widening of `commonMain` visibility is permitted by this plan.
- **`commonMain` stays the shared brain:** all new code in this plan lives in `iosMain`/`iosTest`. Do **not** add iOS-only logic to `commonMain`.
- **iOS deployment base = 15;** no behavior change to existing types.
- **ktlint lints `iosMain`/`iosTest`:** Official Kotlin style, no wildcard imports. Watch `no-consecutive-comments` (a KDoc may not be immediately followed by a `//` comment or another KDoc) and `class-signature` (a single primary-constructor parameter goes on its own line).
- **detekt is `maxIssues: 0`** — fix root causes, never `@Suppress`.
- **`internal` visibility for everything not part of the public SDK surface;** `data class` for models, `sealed interface` for hierarchies (already established — not changed here).
- **No commit without these green** (the iOS-relevant subset of the CI gate): `:sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeDebugXCFramework :sdk:ktlintCheck`, plus the full gate at plan end.

---

## File Structure

- `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/DiagnosticsSink.kt` — **new.** Public, write-only interface: the narrow surface the Swift probe feeds diagnostics through. Mirrors `SessionStore`'s write API exactly.
- `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/ProbeCore.kt` — **new.** Public holder: owns an `internal` `SessionStore`, exposes the common `OverlayPresenter`, manages the presenter collector lifecycle (`start`/`stop`), and implements `DiagnosticsSink` by delegating to the store.
- `sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/ProbeCoreTest.kt` — **new.** Unit tests proving the sink delegates writes to the store and `clear` resets it.
- `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt` — **modify.** Promote the primitive-typed pure helpers from `internal` to `public` (the Swift layer will call them). AVFoundation-typed functions stay `internal`.
- `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVAccessLogMappers.kt` — **modify.** Promote its primitive-typed helpers from `internal` to `public`.

---

## Task 1: `ProbeCore` holder + `DiagnosticsSink` write interface

**Files:**
- Create: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/DiagnosticsSink.kt`
- Create: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/ProbeCore.kt`
- Test: `sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/ProbeCoreTest.kt`

**Interfaces:**
- Consumes: `SessionStore` (`internal`, commonMain) and its write methods `updateTrackList(TrackListInfo)`, `updateActiveTrack(ActiveTrackInfo)`, `updateActiveAudioTrack(AudioTrackInfo?)`, `updateActiveSubtitleTrack(SubtitleTrackInfo?)`, `addSegmentMetric(SegmentMetric)`, `addTrackSwitchEvent(TrackSwitchEvent)`, `addPlaybackError(PlaybackErrorEvent)`, `addDrmSessionEvent(DrmSessionEvent)`, `updateDrmState(DrmStatusInfo?)`, `clearPlaybackErrors()`, `clear()`; read flows `activeTrack`, `segmentMetrics`, `latestSegmentMetric`. `OverlayPresenter(SessionStore)` (`internal constructor`) and `OverlayPresenter.start(CoroutineScope)` (`internal`). The primitive helpers `activeTrackFromIndicatedBitrate(Double): ActiveTrackInfo` and `accessLogSegmentMetric(nowMs: Long, uri: String?, sizeBytes: Long, observedBitrate: Double, transferDurationSeconds: Double): SegmentMetric` (used by the test to build models without knowing every field).
- Produces:
  - `interface DiagnosticsSink` — the 11 write methods listed above.
  - `class ProbeCore : DiagnosticsSink` with `public val presenter: OverlayPresenter`, `public fun start()`, `public fun stop()`, and `internal val sessionStore: SessionStore` (for tests). These are what the Swift layer (Plan 2) and the iOS entry point will consume.

- [ ] **Step 1: Write the failing test**

Create `sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/ProbeCoreTest.kt`:

```kotlin
package com.streamprobe.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ProbeCore]: proves the [DiagnosticsSink] writes reach the wrapped
 * [SessionStore] and that [ProbeCore.clear] resets it. Models are built via the existing
 * primitive mapper helpers so the test needs no knowledge of every model field.
 */
class ProbeCoreTest {
    @Test
    fun updateActiveTrack_writesThroughToStore() {
        val core = ProbeCore()
        val track = activeTrackFromIndicatedBitrate(1_500_000.0)
        core.updateActiveTrack(track)
        assertEquals(track, core.sessionStore.activeTrack.value)
    }

    @Test
    fun addSegmentMetric_writesThroughToStore() {
        val core = ProbeCore()
        val metric =
            accessLogSegmentMetric(
                nowMs = 1_000L,
                uri = "seg-1.ts",
                sizeBytes = 1_000L,
                observedBitrate = 8_000.0,
                transferDurationSeconds = 1.0,
            )
        core.addSegmentMetric(metric)
        assertEquals(metric, core.sessionStore.latestSegmentMetric.value)
        assertEquals(metric, core.sessionStore.segmentMetrics.value.lastOrNull())
    }

    @Test
    fun clear_resetsTheStore() {
        val core = ProbeCore()
        core.updateActiveTrack(activeTrackFromIndicatedBitrate(1_000_000.0))
        core.addSegmentMetric(
            accessLogSegmentMetric(
                nowMs = 2_000L,
                uri = "seg-2.ts",
                sizeBytes = 500L,
                observedBitrate = 4_000.0,
                transferDurationSeconds = 1.0,
            ),
        )
        core.clear()
        assertNull(core.sessionStore.activeTrack.value)
        assertEquals(emptyList(), core.sessionStore.segmentMetrics.value)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :sdk:iosSimulatorArm64Test --tests "com.streamprobe.sdk.internal.ProbeCoreTest"`
Expected: **compilation failure** — `ProbeCore` is unresolved (`activeTrackFromIndicatedBitrate` / `accessLogSegmentMetric` resolve; `ProbeCore` does not).

- [ ] **Step 3: Create the `DiagnosticsSink` interface**

Create `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/DiagnosticsSink.kt`:

```kotlin
package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackListInfo
import com.streamprobe.sdk.model.TrackSwitchEvent

/**
 * Narrow, write-only surface the Swift `StreamProbeIOS` probe uses to feed diagnostics into the
 * Core. Implemented by [ProbeCore], which delegates each call to the internal [SessionStore].
 * Read access and the rendered view-state are exposed separately via `ProbeCore.presenter`.
 */
public interface DiagnosticsSink {
    public fun updateTrackList(info: TrackListInfo)

    public fun updateActiveTrack(info: ActiveTrackInfo)

    public fun updateActiveAudioTrack(info: AudioTrackInfo?)

    public fun updateActiveSubtitleTrack(info: SubtitleTrackInfo?)

    public fun addSegmentMetric(metric: SegmentMetric)

    public fun addTrackSwitchEvent(event: TrackSwitchEvent)

    public fun addPlaybackError(event: PlaybackErrorEvent)

    public fun addDrmSessionEvent(event: DrmSessionEvent)

    public fun updateDrmState(info: DrmStatusInfo?)

    public fun clearPlaybackErrors()

    public fun clear()
}
```

- [ ] **Step 4: Create the `ProbeCore` holder**

Create `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/ProbeCore.kt`:

```kotlin
package com.streamprobe.sdk.internal

import com.streamprobe.sdk.internal.presenter.OverlayPresenter
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackListInfo
import com.streamprobe.sdk.model.TrackSwitchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * iOS-only Core facade consumed by the Swift `StreamProbeIOS` layer.
 *
 * Bundles the (internal) [SessionStore], the common [OverlayPresenter], and the presenter
 * collector lifecycle behind a narrow [DiagnosticsSink] write surface. The Swift probe writes
 * diagnostics through the sink methods; the Swift overlay observes [presenter] (via SKIE) and
 * drives [start]/[stop].
 *
 * Lives in `iosMain` (not `commonMain`): all source sets share one module, so this reaches
 * [SessionStore]'s `internal` writes directly while keeping the store off the public surface.
 */
public class ProbeCore : DiagnosticsSink {
    private val store = SessionStore()

    /** Exposed internally so `iosTest` can assert store contents. */
    internal val sessionStore: SessionStore get() = store

    /** Common presenter; observe [OverlayPresenter.viewState] via SKIE to drive the overlay. */
    public val presenter: OverlayPresenter = OverlayPresenter(store)

    private var presenterScope: CoroutineScope? = null

    /** Starts the presenter collectors on the main dispatcher. Idempotent. */
    public fun start() {
        if (presenterScope != null) return
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        presenterScope = scope
        presenter.start(scope)
    }

    /** Cancels the presenter collectors so live updates freeze. Idempotent. */
    public fun stop() {
        presenterScope?.cancel()
        presenterScope = null
    }

    override fun updateTrackList(info: TrackListInfo) {
        store.updateTrackList(info)
    }

    override fun updateActiveTrack(info: ActiveTrackInfo) {
        store.updateActiveTrack(info)
    }

    override fun updateActiveAudioTrack(info: AudioTrackInfo?) {
        store.updateActiveAudioTrack(info)
    }

    override fun updateActiveSubtitleTrack(info: SubtitleTrackInfo?) {
        store.updateActiveSubtitleTrack(info)
    }

    override fun addSegmentMetric(metric: SegmentMetric) {
        store.addSegmentMetric(metric)
    }

    override fun addTrackSwitchEvent(event: TrackSwitchEvent) {
        store.addTrackSwitchEvent(event)
    }

    override fun addPlaybackError(event: PlaybackErrorEvent) {
        store.addPlaybackError(event)
    }

    override fun addDrmSessionEvent(event: DrmSessionEvent) {
        store.addDrmSessionEvent(event)
    }

    override fun updateDrmState(info: DrmStatusInfo?) {
        store.updateDrmState(info)
    }

    override fun clearPlaybackErrors() {
        store.clearPlaybackErrors()
    }

    override fun clear() {
        store.clear()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :sdk:iosSimulatorArm64Test --tests "com.streamprobe.sdk.internal.ProbeCoreTest"`
Expected: **PASS** (3 tests).

- [ ] **Step 6: Verify ktlint is clean on the new files**

Run: `./gradlew :sdk:ktlintCheck`
Expected: **BUILD SUCCESSFUL** (no `no-consecutive-comments` / `class-signature` violations — `ProbeCore` has no primary-constructor parameters and no consecutive comments).

- [ ] **Step 7: Commit**

```bash
git add sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/DiagnosticsSink.kt \
        sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/ProbeCore.kt \
        sdk/src/iosTest/kotlin/com/streamprobe/sdk/internal/ProbeCoreTest.kt
git commit -m "feat(kmp/ios): add ProbeCore facade + DiagnosticsSink write surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Promote the primitive mapping helpers to `public`

The Swift probe (Plan 2) extracts AVFoundation fields into primitives and calls these pure helpers for the real mapping math. They are currently `internal` (module-visible only), so they must become `public` to be exported to the framework and callable from Swift. The AVFoundation-typed functions (`mapVariant`, `mapAudioOption`, `mapLegibleOption`) stay `internal` — they are consumed only by the soon-to-be-removed Kotlin `AVPlayerProbe` and are deleted in a later plan.

This is a visibility-only refactor: no behavior change, so the existing `AVMetricMappersTest` and `AVAccessLogMappersTest` (which already exercise these helpers) remain the regression net. True cross-language verification happens in Plan 2 when Swift imports them.

**Files:**
- Modify: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt`
- Modify: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVAccessLogMappers.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (now `public`, signatures unchanged): from `AVMetricMappers.kt` — `pickVariantBitrate(peakBitRate: Double, averageBitRate: Double): Int`, `dimensionOrUnknown(value: Double): Int`, `frameRateOrUnknown(nominalFrameRate: Double): Float`, `joinCodecs(codecTypes: List<String>): String?`, `fourCCToString(value: Int): String`, `preferredLanguageTag(extendedLanguageTag: String?, localeLanguageCode: String?): String?`, `defaultSubtitleKind(): SubtitleKind`; from `AVAccessLogMappers.kt` — `secondsToMillis(seconds: Double): Long`, `segmentThroughput(observedBitrate: Double, sizeBytes: Long, durationMs: Long): Long`, `accessLogSegmentMetric(nowMs: Long, uri: String?, sizeBytes: Long, observedBitrate: Double, transferDurationSeconds: Double): SegmentMetric`, `activeTrackFromIndicatedBitrate(indicatedBitrate: Double): ActiveTrackInfo`, `isBitrateSwitch(previousIndicated: Double, currentIndicated: Double): Boolean`, `droppedFramesError(nowMs: Long, droppedFrames: Long): PlaybackErrorEvent`, `loadError(nowMs: Long, errorDomain: String?, statusCode: Long, uri: String?, comment: String?): PlaybackErrorEvent`, `loadErrorMessage(errorDomain: String?, statusCode: Long, uri: String?): String`.

- [ ] **Step 1: Promote the `AVMetricMappers.kt` primitive helpers**

In `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt`, change the visibility modifier from `internal` to `public` on these seven top-level functions only (leave their bodies and KDoc unchanged):

- `internal fun pickVariantBitrate(` → `public fun pickVariantBitrate(`
- `internal fun dimensionOrUnknown(` → `public fun dimensionOrUnknown(`
- `internal fun frameRateOrUnknown(` → `public fun frameRateOrUnknown(`
- `internal fun joinCodecs(` → `public fun joinCodecs(`
- `internal fun fourCCToString(` → `public fun fourCCToString(`
- `internal fun preferredLanguageTag(` → `public fun preferredLanguageTag(`
- `internal fun defaultSubtitleKind(` → `public fun defaultSubtitleKind(`

Leave `internal fun mapVariant(`, `internal fun mapAudioOption(`, `internal fun mapLegibleOption(`, and the `private` `readPresentationSize` / `videoCodecs` exactly as they are.

- [ ] **Step 2: Promote the `AVAccessLogMappers.kt` primitive helpers**

In `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVAccessLogMappers.kt`, change `internal` to `public` on these eight top-level functions only (bodies/KDoc unchanged):

- `internal fun secondsToMillis(` → `public fun secondsToMillis(`
- `internal fun segmentThroughput(` → `public fun segmentThroughput(`
- `internal fun accessLogSegmentMetric(` → `public fun accessLogSegmentMetric(`
- `internal fun activeTrackFromIndicatedBitrate(` → `public fun activeTrackFromIndicatedBitrate(`
- `internal fun isBitrateSwitch(` → `public fun isBitrateSwitch(`
- `internal fun droppedFramesError(` → `public fun droppedFramesError(`
- `internal fun loadError(` → `public fun loadError(`
- `internal fun loadErrorMessage(` → `public fun loadErrorMessage(`

Leave the `private const`/`private val` (`BITS_PER_BYTE`, `UNKNOWN_URI`, `UNKNOWN_CDN_INFO`) unchanged.

- [ ] **Step 3: Verify the existing mapper tests still pass and the framework still links**

Run: `./gradlew :sdk:iosSimulatorArm64Test --tests "com.streamprobe.sdk.internal.AVMetricMappersTest" --tests "com.streamprobe.sdk.internal.AVAccessLogMappersTest"`
Expected: **PASS** (behavior unchanged).

Run: `./gradlew :sdk:assembleStreamProbeDebugXCFramework`
Expected: **BUILD SUCCESSFUL** — the now-public helpers export cleanly into the framework.

- [ ] **Step 4: Verify ktlint**

Run: `./gradlew :sdk:ktlintCheck`
Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt \
        sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVAccessLogMappers.kt
git commit -m "refactor(kmp/ios): expose primitive mapping helpers as public for the Swift layer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification (full CI gate)

- [ ] **Run the full gate and confirm green:**

```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```

Expected: **BUILD SUCCESSFUL**. Nothing in this plan touches `commonMain`, `androidMain`, or the demo, so Android and the existing iOS XCFramework/demo are unaffected; the only additions are the new `iosMain` facade and the iOS visibility promotions.

---

## Subsequent Plans (deferred — authored when each starts)

Per this repo's roadmap philosophy ("granular per-step code can only be written honestly once the preceding phase has landed"), and because the Swift code below depends on the SKIE-generated Swift names that only exist after Plan 1's surface is built and the framework is regenerated, Plans 2 and 3 are outlined here at roadmap altitude and expanded into bite-sized plans when started:

- **Plan 2 — Swift `StreamProbeIOS` layer + `Package.swift`.** Rename framework `baseName` `StreamProbe` → `StreamProbeCore`; add the `zipReleaseXCFramework` Gradle task; create `Sources/StreamProbeIOS/` with `StreamProbe.swift` (entry: `attach(AVPlayer)`, `show`/`hide`, `detach`, overlay-window installer), `AVPlayerProbe.swift` (AVFoundation observation → primitive extraction → Core public mappers → `ProbeCore` `DiagnosticsSink`), and the 15 moved overlay Swift files; author root `Package.swift` (binary `StreamProbeCore` + source `StreamProbeIOS`, `@_exported import StreamProbeCore`); verify a throwaway SPM consumer resolves and `import StreamProbe` exposes the API; XCTest for the Swift probe. *(Demo not yet migrated; old Kotlin probe still present — both addressed in Plan 3.)*
- **Plan 3 — Demo migration, Kotlin cleanup, CI.** Point `iosApp/` at the local SPM package and delete its `Overlay/` copy; delete the Kotlin `AVPlayerProbe`, `StreamProbe.ios.kt`, the AVFoundation-typed mapper functions, and the `expect/actual StreamProbe` **class** (Android gets a plain `androidMain` `StreamProbe` with identical public API); drop the `-Xexpect-actual-classes` flag; add `IOS_VERSION_NAME=0.1.0` and the `publish-spm.yml` workflow (draft-release flow); live-verify the Swift probe on a simulator/device.
```
