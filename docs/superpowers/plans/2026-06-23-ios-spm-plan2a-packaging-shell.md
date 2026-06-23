# iOS Two-Layer / SPM — Plan 2a: SPM Packaging Shell

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the SPM package structure — rename the Kotlin framework to `StreamProbeCore`, add a Gradle task that zips the Release XCFramework and prints its SwiftPM checksum, and author a root `Package.swift` (env-conditional binary target) with a minimal `Sources/StreamProbeIOS` source target — then **prove a source SPM target can consume the SKIE-bridged Core binary on iOS** via an `xcodebuild` smoke build. No probe port, no overlay move yet (Plan 2b).

**Architecture:** Per `docs/superpowers/specs/2026-06-23-ios-two-layer-spm-packaging-design.md`. This plan de-risks the single biggest unknown — that a Swift source SPM target depending on a binary `.xcframework` target exposes the SKIE-enhanced Core API and links for iOS — before any logic is ported. The Swift `StreamProbe` entry point, the `AVPlayerProbe` Swift port, and the overlay move all come in Plan 2b.

**Tech Stack:** Swift Package Manager (`swift-tools-version:5.9`), Kotlin Multiplatform XCFramework (static, SKIE), Gradle (`Exec` task), `xcodebuild`, `ditto`, `swift package compute-checksum`.

## Global Constraints

- **Framework rename:** the Kotlin XCFramework's `XCFramework("StreamProbe")` and `baseName = "StreamProbe"` both become **`StreamProbeCore`**. Consequently the Gradle task names change: `assembleStreamProbe*XCFramework` → `assembleStreamProbeCore*XCFramework`; the output path becomes `sdk/build/XCFrameworks/{debug,release}/StreamProbeCore.xcframework`. Swift code is unaffected by the ObjC-prefix change (SKIE `NS_SWIFT_NAME` keeps Swift symbols unprefixed: `ProbeCore`, `VariantInfo`, …); only the Swift `import` name changes from `StreamProbe` to `StreamProbeCore`.
- **SPM layout (final shape):** product `.library(name: "StreamProbe", targets: ["StreamProbeIOS"])`; source target `StreamProbeIOS` (path `Sources/StreamProbeIOS`) depends on binary target `StreamProbeCore`; `StreamProbeIOS` does `@_exported import StreamProbeCore` so consumers `import StreamProbe`.
- **Env-conditional binary target:** `Package.swift` reads `ProcessInfo.processInfo.environment["STREAMPROBE_LOCAL"]`; when set → `.binaryTarget(name: "StreamProbeCore", path: "sdk/build/XCFrameworks/release/StreamProbeCore.xcframework")`; otherwise → `.binaryTarget(name: "StreamProbeCore", url: "https://github.com/oguzhaneksi/StreamProbe/releases/download/v<IOS_VERSION>/StreamProbeCore.xcframework.zip", checksum: <set by publish-spm.yml at release>)`. iOS version is **0.1.0** (first iOS release; the remote `checksum` is a documented zero placeholder until Plan 3's first release fills it — the local branch is what works now).
- **Platform:** `platforms: [.iOS(.v15)]`. The package is iOS-only, so `swift build` (macOS) cannot link it — all build verification uses `xcodebuild` with an iOS Simulator destination.
- **The iosApp demo will NOT build after the rename** (its `import StreamProbe` + `project.yml` framework reference break). This is intentional and expected; the demo is migrated to the SPM package in Plan 3. **Do not modify `iosApp/` in this plan.**
- **The Gradle CI gate does not build iosApp**, so it stays green. Use the renamed XCFramework task in the gate: `:sdk:assembleStreamProbeCoreDebugXCFramework`.
- **Commit per task** (authorized); **never `git push`** without explicit approval.

---

## File Structure

- `sdk/build.gradle.kts` — **modify.** Rename `XCFramework("StreamProbe")`/`baseName` → `StreamProbeCore`; add the `zipReleaseXCFramework` task.
- `Package.swift` — **new** (repo root). Env-conditional binary `StreamProbeCore` + source `StreamProbeIOS`; product `StreamProbe`.
- `Sources/StreamProbeIOS/StreamProbeIOS.swift` — **new.** `@_exported import StreamProbeCore` + a tiny factory that references a Core type, forcing the source target to actually compile against the SKIE-bridged binary API. Superseded by the real entry point in Plan 2b.
- `.gitignore` — **modify (if needed).** Ignore `sdk/build/XCFrameworks/**/*.xcframework.zip` and the smoke-build derived-data dir if placed in the repo.

---

## Task 1: Rename framework to `StreamProbeCore` + add `zipReleaseXCFramework` Gradle task

**Files:**
- Modify: `sdk/build.gradle.kts`

**Interfaces:**
- Consumes: the existing `kotlin { … XCFramework("StreamProbe") … baseName = "StreamProbe" … }` block (lines ~42-53 of `sdk/build.gradle.kts`).
- Produces: Gradle tasks `assembleStreamProbeCoreReleaseXCFramework` / `assembleStreamProbeCoreDebugXCFramework` (auto-renamed by the `XCFramework("StreamProbeCore")` change), output `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework`; a new task `:sdk:zipReleaseXCFramework` that writes `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework.zip` and prints its `swift package compute-checksum` value.

- [ ] **Step 1: Rename the XCFramework and baseName**

In `sdk/build.gradle.kts`, change the XCFramework declaration and the per-target framework `baseName`:

```kotlin
        val xcf = XCFramework("StreamProbeCore")
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
            iosX64(),
        ).forEach { target ->
            target.binaries.framework {
                baseName = "StreamProbeCore"
                isStatic = true
                xcf.add(this)
            }
        }
```

- [ ] **Step 2: Verify the renamed Debug XCFramework builds**

Run: `./gradlew :sdk:assembleStreamProbeCoreDebugXCFramework`
Expected: **BUILD SUCCESSFUL**; `sdk/build/XCFrameworks/debug/StreamProbeCore.xcframework` exists (`ls sdk/build/XCFrameworks/debug/`).

- [ ] **Step 3: Add the `zipReleaseXCFramework` task**

Append to `sdk/build.gradle.kts` (top level, after the `kotlin { }` block; keep imports at the top of the file if any are needed — none are for this `Exec` task):

```kotlin
tasks.register<Exec>("zipReleaseXCFramework") {
    group = "build"
    description = "Zips the Release StreamProbeCore XCFramework and prints its SwiftPM checksum."
    dependsOn("assembleStreamProbeCoreReleaseXCFramework")
    workingDir = layout.buildDirectory.dir("XCFrameworks/release").get().asFile
    commandLine(
        "bash", "-c",
        "rm -f StreamProbeCore.xcframework.zip && " +
            "ditto -c -k --keepParent StreamProbeCore.xcframework StreamProbeCore.xcframework.zip && " +
            "printf 'CHECKSUM ' && swift package compute-checksum StreamProbeCore.xcframework.zip",
    )
}
```

- [ ] **Step 4: Verify the zip task produces a zip + prints a checksum**

Run: `./gradlew :sdk:zipReleaseXCFramework`
Expected: **BUILD SUCCESSFUL**; output contains a line `CHECKSUM <64-hex-chars>`; `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework.zip` exists. (This step also builds the Release XCFramework via the `dependsOn`, which is a slow Kotlin/Native release compile on first run — expected.)

- [ ] **Step 5: Ignore the generated zip in git**

Add to `.gitignore` (if not already covered):

```
sdk/build/XCFrameworks/**/*.xcframework.zip
```

- [ ] **Step 6: Confirm the Gradle gate is still green under the new task name**

Run: `./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`
Expected: **BUILD SUCCESSFUL** (the rename does not affect Android, commonMain, or the iOS test sources; iosApp is not a Gradle target).

- [ ] **Step 7: Commit**

```bash
git add sdk/build.gradle.kts .gitignore
git commit -m "build(kmp/ios): rename XCFramework to StreamProbeCore + add zipReleaseXCFramework

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `Package.swift` + `Sources/StreamProbeIOS` skeleton + iOS smoke build

**Files:**
- Create: `Package.swift` (repo root)
- Create: `Sources/StreamProbeIOS/StreamProbeIOS.swift`

**Interfaces:**
- Consumes: the binary `StreamProbeCore.xcframework` produced by Task 1 (`sdk/build/XCFrameworks/release/StreamProbeCore.xcframework`); the SKIE-bridged Swift symbol `ProbeCore` (init `ProbeCore()`).
- Produces: an SPM package with product `StreamProbe` (→ target `StreamProbeIOS` → binary `StreamProbeCore`); the public Swift symbol `StreamProbeKit.makeCore() -> ProbeCore` (temporary smoke surface, replaced in Plan 2b).

- [ ] **Step 1: Build the Release XCFramework the local binary target points at**

Run: `./gradlew :sdk:assembleStreamProbeCoreReleaseXCFramework`
Expected: **BUILD SUCCESSFUL**; `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework` exists. (Required so the env-conditional local path resolves in the smoke build below.)

- [ ] **Step 2: Write `Package.swift`**

Create `Package.swift` at the repo root:

```swift
// swift-tools-version:5.9
import PackageDescription
import Foundation

// Dev/CI iteration uses the locally-built XCFramework (set STREAMPROBE_LOCAL=1 in the
// environment); external consumers resolve the released zip. The remote `checksum` is a
// placeholder filled by `.github/workflows/publish-spm.yml` at the first release (Plan 3).
let useLocalBinary = ProcessInfo.processInfo.environment["STREAMPROBE_LOCAL"] != nil

let coreBinaryTarget: Target = useLocalBinary
    ? .binaryTarget(
        name: "StreamProbeCore",
        path: "sdk/build/XCFrameworks/release/StreamProbeCore.xcframework"
    )
    : .binaryTarget(
        name: "StreamProbeCore",
        url: "https://github.com/oguzhaneksi/StreamProbe/releases/download/v0.1.0/StreamProbeCore.xcframework.zip",
        checksum: "0000000000000000000000000000000000000000000000000000000000000000"
    )

let package = Package(
    name: "StreamProbe",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "StreamProbe", targets: ["StreamProbeIOS"]),
    ],
    targets: [
        coreBinaryTarget,
        .target(
            name: "StreamProbeIOS",
            dependencies: ["StreamProbeCore"],
            path: "Sources/StreamProbeIOS"
        ),
    ]
)
```

- [ ] **Step 3: Write the `Sources/StreamProbeIOS` skeleton**

Create `Sources/StreamProbeIOS/StreamProbeIOS.swift`:

```swift
@_exported import StreamProbeCore

/// Umbrella for the StreamProbe iOS layer. Re-exports the Kotlin Core (`StreamProbeCore`) so
/// consumers `import StreamProbe` and get the Core types directly. The Swift entry point
/// (`attach`/`show`/`hide`), the AVFoundation probe, and the overlay UI are added in Plan 2b.
public enum StreamProbeKit {
    /// Constructs a fresh Core holder. Temporary smoke surface that forces this source target to
    /// compile against the SKIE-bridged binary API; replaced by the real entry point in Plan 2b.
    public static func makeCore() -> ProbeCore { ProbeCore() }
}
```

- [ ] **Step 4: Verify `Package.swift` parses and resolves the targets**

Run: `STREAMPROBE_LOCAL=1 swift package dump-package > /dev/null && echo PARSE_OK`
Expected: prints `PARSE_OK` (the manifest is valid Swift and the env-conditional local target resolves). Without `STREAMPROBE_LOCAL`, `dump-package` also parses but selects the remote target — do not resolve that branch (no release exists yet).

- [ ] **Step 5: Smoke-build the package for iOS Simulator (the de-risk)**

Run from the repo root:

```bash
STREAMPROBE_LOCAL=1 xcodebuild \
  -scheme StreamProbe \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke \
  build
```

Expected: **BUILD SUCCEEDED**. This compiles `StreamProbeIOS` (which references `ProbeCore` via `@_exported import StreamProbeCore`) and links the binary `StreamProbeCore.xcframework`'s simulator slice — proving a source SPM target consumes the SKIE-bridged Core binary on iOS. (`xcodebuild` evaluates `Package.swift` in the invoking shell's environment, so `STREAMPROBE_LOCAL=1` reaches the manifest.)

If the scheme name is not found, list available schemes with `xcodebuild -list` (run in the repo root) and use the package's product scheme.

- [ ] **Step 6: Ignore the smoke derived-data dir**

Add to `.gitignore`:

```
sdk/build/spm-smoke/
```

(Already under `sdk/build/`, which is typically git-ignored — confirm `git status` does not show it; add the line only if it appears.)

- [ ] **Step 7: Commit**

```bash
git add Package.swift Sources/StreamProbeIOS/StreamProbeIOS.swift .gitignore
git commit -m "build(kmp/ios): add Package.swift (env-conditional) + StreamProbeIOS skeleton

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **Gradle gate green (renamed task):**

```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```
Expected: **BUILD SUCCESSFUL**.

- [ ] **SPM smoke build green:** `STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' -derivedDataPath sdk/build/spm-smoke build` → **BUILD SUCCEEDED**.

- [ ] **Known-red (expected, not a regression):** the `iosApp/` Xcode project no longer builds (its `import StreamProbe` + `project.yml` reference the old framework name). This is migrated in Plan 3; do not fix it here.

---

## Subsequent Plans (deferred — authored when each starts)

- **Plan 2b — Swift `StreamProbeIOS` layer (logic + UI).** Move the 15 overlay Swift files from `iosApp/iosApp/Overlay/` into `Sources/StreamProbeIOS/` (changing `import StreamProbe` → `import StreamProbeCore`/umbrella); add `StreamProbe.swift` entry point (`attach(AVPlayer)`, `show`/`hide`, `detach`, overlay-window installer); port `AVPlayerProbe` to Swift (`AVPlayerProbe.swift`) replicating the Kotlin logic — variants async-load with stale-closure guard, access-log finalization-one-behind, bitrate-switch detection, closest-variant active-track resolution, audio-selection refresh, monotonic clock — feeding `ProbeCore`'s `DiagnosticsSink` and keeping local mirror state for the previous-track/snapshot reads (since the sink is write-only); Swift mapping helpers using the public `AVMetricMappersKt`/`AVAccessLogMappersKt` static functions + the model initializers; replace the temporary `StreamProbeKit.makeCore()`; XCTest for the Swift probe; live-verify on a simulator/device. (The `ProbeCore`/`DiagnosticsSink` Kotlin facade stays in package `com.streamprobe.sdk.internal` — Kotlin packages are invisible to Swift via SKIE/ObjC, so the placement has no Swift impact.)
- **Plan 3 — Demo migration, Kotlin cleanup, CI.** Migrate `iosApp/` to consume the local SPM package (XcodeGen `packages:` local path, `STREAMPROBE_LOCAL`) and delete its `Overlay/` copy; delete the Kotlin `AVPlayerProbe`, `StreamProbe.ios.kt`, the AVFoundation-typed mapper functions, and the `expect/actual StreamProbe` class (drop `-Xexpect-actual-classes`; Android gets a plain `androidMain` `StreamProbe`); add `IOS_VERSION_NAME=0.1.0` + `publish-spm.yml` (builds Release XCFramework via `zipReleaseXCFramework`, fills `Package.swift` url+checksum, tags `v0.1.0`, drafts the release).
