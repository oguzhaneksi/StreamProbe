# iOS Two-Layer / SPM — Plan 2b: Swift Overlay + Entry Point Shell

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the 15 overlay Swift files into the `StreamProbeIOS` SPM source target and add the public Swift `StreamProbe` entry point (`attach`/`show`/`hide`/`detach`, `overlayPresenter`, and a `makeOverlayWindow(windowScene:)` installer) — producing a package that **builds for iOS**, with the overlay UI rendering `OverlayPresenter.viewState` via SKIE. The AVFoundation probe that feeds the Core is **deferred to Plan 2c**; in this plan `attach(player:)` only resets the session.

**Architecture:** Per `docs/superpowers/specs/2026-06-23-ios-two-layer-spm-packaging-design.md`. Plan 2a proved a source SPM target consumes the SKIE Core binary on iOS. This plan moves the host app's overlay UI into that source target (it stays `internal`) and gives the package a small public surface — the `StreamProbe` entry point — that encapsulates the overlay-window creation the demo's `SceneDelegate` does manually today. No probe, no Gradle/Kotlin changes.

**Tech Stack:** Swift (`StreamProbeIOS` source target), UIKit, AVFoundation (`AVPlayer` type only, in the entry-point signature), SKIE-bridged Core types, `xcodebuild` (iOS Simulator).

## Global Constraints

- **All overlay files stay `internal`** to the `StreamProbeIOS` module. The only public surface this plan adds is the `StreamProbe` class and its members. The host app uses `makeOverlayWindow(windowScene:)` — it never touches `OverlayHostViewController` / `StreamProbeOverlayWindow` directly.
- **Module/import rename:** every moved overlay file that currently does `import StreamProbe` must become `import StreamProbeCore` (the framework was renamed in Plan 2a). Files importing only `UIKit`/`Foundation` are unchanged. If any file uses a module-qualified reference `StreamProbe.SomeType`, change it to `StreamProbeCore.SomeType` (the iOS build will reveal these).
- **Move, don't copy:** use `git mv` so history is preserved; the files are removed from `iosApp/iosApp/Overlay/`. The iosApp Xcode project is already non-building (Plan 2a rename) and is fully migrated to consume the package in Plan 3 — do not try to keep iosApp building in this plan.
- **`attach(player:)` body is staged:** in this plan it only calls `core.clear()` (the AVFoundation probe is wired in Plan 2c). The `player` parameter is intentionally unused for now; keep the public signature stable.
- **Verification is the iOS build**, not unit tests: this layer is UIKit/SKIE glue (the pure logic is already covered by Kotlin `iosTest`), so the gate is `STREAMPROBE_LOCAL=1 xcodebuild ... build` succeeding — which proves the overlay + SKIE `onEnum`/`viewState` bridging + the entry point all compile and link against the Core binary.
- **The Gradle CI gate is unaffected** (no Kotlin/Gradle change) but should remain green; iosApp remains known-red until Plan 3.
- **Commit per task** (authorized); **never `git push`** without explicit approval.

---

## File Structure

- `Sources/StreamProbeIOS/Overlay/` — **new dir.** The 15 moved overlay files (all `internal`):
  `ChipBarView.swift`, `DrmCell.swift`, `ErrorCell.swift`, `ErrorsHeaderView.swift`, `HeaderView.swift`, `OverlayFormattersSwift.swift`, `OverlayHostViewController.swift`, `OverlayPanelView.swift`, `OverlayTableDataSource.swift`, `OverlayTheme.swift`, `RenditionCell.swift`, `SegmentCell.swift`, `StatsView.swift`, `StreamProbeOverlayWindow.swift`, `SwitchCell.swift`.
- `Sources/StreamProbeIOS/StreamProbe.swift` — **new.** The public Swift entry point + overlay-window installer.
- `Sources/StreamProbeIOS/StreamProbeIOS.swift` — **modify.** Remove the temporary `StreamProbeKit.makeCore()` smoke surface (superseded by `StreamProbe`); keep `@_exported import StreamProbeCore`.
- `iosApp/iosApp/Overlay/` — **removed** (files moved out).

---

## Task 1: Move the 15 overlay files into `Sources/StreamProbeIOS/Overlay` and fix imports

**Files:**
- Move (git mv): `iosApp/iosApp/Overlay/*.swift` → `Sources/StreamProbeIOS/Overlay/*.swift` (15 files)
- Modify (import line) in the 13 files that import the framework.

**Interfaces:**
- Consumes: the `StreamProbeCore` binary target's SKIE-bridged types (`OverlayPresenter`, `OverlayViewState`, `OverlayRow`, `TrackSwitchEvent`, `DrmSessionEvent`, `ViewMode`, `PlaybackErrorEvent`, `SegmentMetric`, `onEnum(of:)`, the model types) — already proven reachable from the source target in Plan 2a.
- Produces: the overlay UI as `internal` types inside the `StreamProbeIOS` module — notably `OverlayHostViewController(presenter:)` and `StreamProbeOverlayWindow(windowScene:)`, used by the entry point in Task 2.

- [ ] **Step 1: Ensure the Release XCFramework exists for the local smoke build**

Run: `./gradlew :sdk:assembleStreamProbeCoreReleaseXCFramework`
Expected: **BUILD SUCCESSFUL**; `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework` exists. (Slow Kotlin/Native release compile on first run — expected. Skip if already present and unchanged.)

- [ ] **Step 2: Move the 15 overlay files with git mv**

```bash
mkdir -p Sources/StreamProbeIOS/Overlay
git mv iosApp/iosApp/Overlay/ChipBarView.swift            Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/DrmCell.swift                Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/ErrorCell.swift              Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/ErrorsHeaderView.swift       Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/HeaderView.swift             Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/OverlayFormattersSwift.swift Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/OverlayHostViewController.swift Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/OverlayPanelView.swift       Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/OverlayTableDataSource.swift Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/OverlayTheme.swift           Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/RenditionCell.swift          Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/SegmentCell.swift            Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/StatsView.swift              Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/StreamProbeOverlayWindow.swift Sources/StreamProbeIOS/Overlay/
git mv iosApp/iosApp/Overlay/SwitchCell.swift             Sources/StreamProbeIOS/Overlay/
```

Confirm `iosApp/iosApp/Overlay/` is now empty: `ls iosApp/iosApp/Overlay/ 2>/dev/null` → nothing (the dir may be removed by git mv).

- [ ] **Step 3: Rewrite the framework import in the moved files**

In the 13 moved files that contain `import StreamProbe`, change that line to `import StreamProbeCore`. (The two files `ErrorsHeaderView.swift` and `StreamProbeOverlayWindow.swift` import only `UIKit` — leave them.) These are the 13:
`ChipBarView.swift`, `DrmCell.swift`, `ErrorCell.swift`, `HeaderView.swift`, `OverlayFormattersSwift.swift`, `OverlayHostViewController.swift`, `OverlayPanelView.swift`, `OverlayTableDataSource.swift`, `OverlayTheme.swift`, `RenditionCell.swift`, `SegmentCell.swift`, `StatsView.swift`, `SwitchCell.swift`.

Apply with a precise replacement across the moved dir:

```bash
grep -rl '^import StreamProbe$' Sources/StreamProbeIOS/Overlay | while read -r f; do
  /usr/bin/sed -i '' 's/^import StreamProbe$/import StreamProbeCore/' "$f"
done
```

Then verify none remain and the new import is present:
```bash
grep -rn '^import StreamProbe$' Sources/StreamProbeIOS/Overlay   # expect: no output
grep -rln '^import StreamProbeCore$' Sources/StreamProbeIOS/Overlay | wc -l   # expect: 13
```

- [ ] **Step 4: Smoke-build the package for iOS Simulator**

Run from the repo root:
```bash
STREAMPROBE_LOCAL=1 xcodebuild \
  -scheme StreamProbe \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke \
  build
```
Expected: **BUILD SUCCEEDED** — the moved overlay files compile against `StreamProbeCore` (including SKIE `onEnum(of:)` / `viewState` usage). If the build reports an unresolved symbol that is a module-qualified `StreamProbe.X` reference, change it to `StreamProbeCore.X` and rebuild; report any such fix.

- [ ] **Step 5: Commit**

```bash
git add Sources/StreamProbeIOS/Overlay
git rm -r --cached iosApp/iosApp/Overlay 2>/dev/null || true
git commit -m "refactor(ios): move overlay UI into the StreamProbeIOS SPM target

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

(The `git mv` already staged the deletions from `iosApp/`; the `git rm --cached` is a no-op safety net. Confirm `git status` shows the 15 files moved, not duplicated.)

---

## Task 2: Public `StreamProbe` entry point + overlay-window installer

**Files:**
- Create: `Sources/StreamProbeIOS/StreamProbe.swift`
- Modify: `Sources/StreamProbeIOS/StreamProbeIOS.swift` (remove the temporary `StreamProbeKit`)

**Interfaces:**
- Consumes: `ProbeCore` (`init()`, `clear()`, `start()`, `stop()`, `presenter: OverlayPresenter`) from `StreamProbeCore`; the `internal` `OverlayHostViewController(presenter:)` and `StreamProbeOverlayWindow(windowScene:)` from Task 1.
- Produces: the public Swift API — `StreamProbe` with `init()`, `attach(player:)`, `show()`, `hide()`, `detach()`, `overlayPresenter: OverlayPresenter`, and `makeOverlayWindow(windowScene:) -> UIWindow`. This is what the demo (Plan 3) and external consumers use; Plan 2c fills in `attach(player:)`'s probe wiring.

- [ ] **Step 1: Write `StreamProbe.swift`**

Create `Sources/StreamProbeIOS/StreamProbe.swift`:

```swift
import AVFoundation
import StreamProbeCore
import UIKit

/// Public iOS entry point for the StreamProbe debug SDK.
///
/// Owns the Kotlin Core (`ProbeCore`): the shared session store, the common `OverlayPresenter`,
/// and the presenter-collector lifecycle. Attach it to an `AVPlayer`, call `show()`, and install
/// the built-in overlay with `makeOverlayWindow(windowScene:)` — or observe `overlayPresenter`
/// directly to drive a custom renderer.
///
/// Not thread-safe: call all methods from the main thread (mirrors `ProbeCore`'s contract). The
/// AVFoundation probe that feeds the Core is wired in a subsequent change; until then `attach`
/// only resets the session.
public final class StreamProbe {
    private let core = ProbeCore()

    public init() {}

    /// The common presenter. Observe `viewState` (SKIE async sequence) to drive a custom overlay,
    /// or use `makeOverlayWindow(windowScene:)` for the built-in one.
    public var overlayPresenter: OverlayPresenter { core.presenter }

    /// Attaches StreamProbe to `player` and clears the previous session. Probe wiring lands in a
    /// later change; the `player` is not yet observed.
    public func attach(player: AVPlayer) {
        core.clear()
    }

    /// Starts the presenter collectors so the overlay updates live. Idempotent.
    public func show() {
        core.start()
    }

    /// Stops the presenter collectors so live updates freeze (the overlay stays visible). Idempotent.
    public func hide() {
        core.stop()
    }

    /// Detaches: stops the collectors and clears the session.
    public func detach() {
        core.stop()
        core.clear()
    }

    /// Creates the built-in always-on-top overlay window for `windowScene`, rendering this probe's
    /// diagnostics via `overlayPresenter`. The caller retains the returned window and controls its
    /// `isHidden` to show/hide the overlay UI (independent of `show()`/`hide()`, which control the
    /// live data feed).
    public func makeOverlayWindow(windowScene: UIWindowScene) -> UIWindow {
        let window = StreamProbeOverlayWindow(windowScene: windowScene)
        window.windowLevel = .alert + 1
        window.backgroundColor = .clear
        let host = OverlayHostViewController(presenter: core.presenter)
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = false
        return window
    }
}
```

- [ ] **Step 2: Remove the temporary smoke surface from `StreamProbeIOS.swift`**

Replace the entire contents of `Sources/StreamProbeIOS/StreamProbeIOS.swift` with just the re-export (the `StreamProbeKit` factory is superseded by `StreamProbe`):

```swift
@_exported import StreamProbeCore

// The public entry point lives in `StreamProbe.swift`. This file only re-exports the Kotlin Core
// (`StreamProbeCore`) so consumers `import StreamProbe` and get both the Core types and the Swift
// entry point + overlay.
```

- [ ] **Step 3: Smoke-build the package for iOS Simulator**

Run from the repo root:
```bash
STREAMPROBE_LOCAL=1 xcodebuild \
  -scheme StreamProbe \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke \
  build
```
Expected: **BUILD SUCCEEDED** — `StreamProbe.swift` compiles, constructing `ProbeCore`, `OverlayHostViewController`, and `StreamProbeOverlayWindow`, and the `StreamProbeKit` removal causes no unresolved references.

- [ ] **Step 4: Commit**

```bash
git add Sources/StreamProbeIOS/StreamProbe.swift Sources/StreamProbeIOS/StreamProbeIOS.swift
git commit -m "feat(ios): add public StreamProbe entry point + overlay-window installer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **SPM smoke build green:** `STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build` → **BUILD SUCCEEDED**. This proves the full overlay UI + the `StreamProbe` entry point + the SKIE bridging compile and link as an SPM source target over the Core binary.

- [ ] **Gradle gate green (unchanged by this plan):**
```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```
Expected: **BUILD SUCCESSFUL** (no Kotlin/Gradle files changed).

- [ ] **Known-red (expected):** `iosApp/` still does not build (its `SceneDelegate` references the now-moved `OverlayHostViewController`/`StreamProbeOverlayWindow` and the old probe API). Migrated in Plan 3. Do not fix here.

---

## Subsequent Plans (deferred — authored when each starts)

- **Plan 2c — AVPlayerProbe Swift port.** Create `Sources/StreamProbeIOS/AVPlayerProbe.swift` porting `sdk/src/iosMain/.../AVPlayerProbe.kt` (variants async-load with stale-closure guard, access-log finalization-one-behind, bitrate-switch detection, closest-variant active-track resolution, audio-selection refresh, monotonic clock, error log) — writing through the `StreamProbe` instance's `ProbeCore` (`DiagnosticsSink`) and keeping local mirror state for the previous-track/snapshot reads (the sink is write-only). Add Swift track-mapping (`AVAssetVariant`/`AVMediaSelectionOption` → models) using the public `AVMetricMappersKt`/`AVAccessLogMappersKt` static functions + model initializers. Wire `StreamProbe.attach(player:)` to create + attach the probe and `detach()` to tear it down. Authored against the now-compiling package so the AVFoundation Swift is written with real compiler feedback. **Live verification is sandbox-blocked** (simulator TLS, per `sdk/src/iosMain/CLAUDE.md`) — gate is build-green + code review; live-verify on a real machine/device.
- **Plan 3 — Demo migration, Kotlin cleanup, CI.** Rewrite `iosApp/`'s `SceneDelegate` to use `probe.makeOverlayWindow(windowScene:)`, point the Xcode project at the local SPM package (XcodeGen `packages:` + `STREAMPROBE_LOCAL`), and delete the iosApp `Overlay/` group references; delete the Kotlin `AVPlayerProbe`, `StreamProbe.ios.kt`, AVFoundation-typed mapper functions, and the `expect/actual StreamProbe` class (drop `-Xexpect-actual-classes`; Android gets a plain `androidMain` `StreamProbe`); add `IOS_VERSION_NAME=0.1.0` + `publish-spm.yml` (clean runner; build Release XCFramework via `zipReleaseXCFramework`; emit checksum to a file; fill `Package.swift` url+checksum; tag `v0.1.0`; draft the release). Carryovers recorded from the Plan 2a review.
```
