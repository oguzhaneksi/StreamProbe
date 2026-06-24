# iOS Two-Layer / SPM — Plan 3: Demo Migration, Kotlin Cleanup, SPM Release CI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Swift two-layer architecture the *only* iOS architecture: migrate the `iosApp/` demo to consume the local `StreamProbeIOS` SPM package + Swift `StreamProbe`, delete the now-superseded Kotlin/Native probe + `expect/actual StreamProbe` class, and add the iOS SPM release pipeline (versioning, `publish-spm.yml`, README).

**Architecture:** Per `docs/superpowers/specs/2026-06-23-ios-two-layer-spm-packaging-design.md`. After Plans 1/2a/2b/2c the Swift layer (`Sources/StreamProbe/`: entry point, overlay, Swift `AVPlayerProbe`, mappers) is complete and builds over the `StreamProbeCore` binary; the demo is still half-migrated (links the old Kotlin XCFramework and references the SKIE-mangled `StreamProbe_` + Swift overlay types that have already moved into the package — hence known-red). This plan repoints the demo at the package, removes the dead Kotlin, and ships the release CI.

**Tech Stack:** Swift/UIKit/SwiftUI demo, XcodeGen, Swift Package Manager, Kotlin Multiplatform (Gradle), GitHub Actions, `gh` CLI.

## Global Constraints

- **iOS deployment floor is iOS 15;** deprecation warnings (iOS-16 async AVFoundation replacements) are expected and acceptable.
- **Android public API stays byte-identical** and the Maven Central flow stays functionally unchanged. The single edit to `publish-sdk.yml` is an additive trigger **guard** (decided: prevent it firing on iOS `vX.Y.Z` releases) — it does not change Android behavior.
- **Framework baseName is `StreamProbeCore`** (already configured) and the local binary lives at `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework`.
- **iOS versioning starts fresh at `0.1.0`** in a **new** `IOS_VERSION_NAME` gradle property — independent of Android's `VERSION_NAME=0.5.0`.
- **Tag namespaces never collide:** Android = `release/vX.Y.Z` (existing), iOS = plain `vX.Y.Z` (new). SPM only reads plain-semver tags.
- **`STREAMPROBE_LOCAL=1`** must be in the environment of every `xcodebuild`/`swift` invocation that resolves the package locally (the manifest reads `ProcessInfo.environment` to switch the binary target from the released zip to the local XCFramework).
- **Verification in this sandbox = builds + Gradle gate green + valid workflow YAML.** Live overlay verification against a real stream is **sandbox-blocked** (simulator TLS, per `sdk/src/iosMain/CLAUDE.md`) and is an explicit **manual follow-up** on a device/simulator with working networking. Running `publish-spm.yml` end-to-end is likewise a manual follow-up on a clean macOS runner.
- **Commit per task** (authorized); **never `git push`** without explicit approval. (The `git push` lines *inside* `publish-spm.yml` run in CI, not from this session.)
- **Prerequisite tool:** XcodeGen (`brew install xcodegen`) to regenerate `iosApp/iosApp.xcodeproj`.

---

## File Structure

**Task 1 — demo migration (modify):**
- `iosApp/project.yml` — replace the local XCFramework dependency with a local SPM `packages:` entry + product dependency.
- `iosApp/iosApp/App/AppDependencies.swift` — `StreamProbe_()` → `StreamProbe()`.
- `iosApp/iosApp/App/SceneDelegate.swift` — build the overlay window via `probe.makeOverlayWindow(windowScene:)`.
- `iosApp/iosApp/Player/PlayerScreen.swift`, `iosApp/iosApp/Settings/SettingsScreen.swift`, `iosApp/iosApp/Streams/StreamSelectionScreen.swift` — `StreamProbe_` → `StreamProbe` in the `probe` property types.

**Task 2 — Kotlin cleanup (delete + modify):**
- Delete: `sdk/src/commonMain/kotlin/com/streamprobe/sdk/StreamProbe.kt` (expect class), `sdk/src/iosMain/kotlin/com/streamprobe/sdk/StreamProbe.ios.kt` (iOS actual), `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVPlayerProbe.kt` (Kotlin probe), `sdk/src/iosTest/kotlin/com/streamprobe/sdk/AVPlayerProbePocTest.kt` (Phase-3 K/N PoC, superseded).
- Modify: `sdk/src/androidMain/kotlin/com/streamprobe/sdk/StreamProbe.kt` (drop `actual`), `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt` (remove AVFoundation-typed funcs + their imports), `sdk/build.gradle.kts` (drop `-Xexpect-actual-classes`).

**Task 3 — versioning + docs (modify):**
- `gradle.properties` — add `IOS_VERSION_NAME=0.1.0`.
- `README.md` — add an iOS / Swift Package Manager install subsection.

**Task 4 — release CI (create + modify):**
- Create: `.github/workflows/publish-spm.yml`.
- Modify: `.github/workflows/publish-sdk.yml` — add the trigger guard.

---

## Task 1: Migrate the iOS demo to the local SPM package + Swift `StreamProbe`

**Files:**
- Modify: `iosApp/project.yml:22-25`
- Modify: `iosApp/iosApp/App/AppDependencies.swift:11`
- Modify: `iosApp/iosApp/App/SceneDelegate.swift:15-25`
- Modify: `iosApp/iosApp/Player/PlayerScreen.swift:10`
- Modify: `iosApp/iosApp/Settings/SettingsScreen.swift:9`
- Modify: `iosApp/iosApp/Streams/StreamSelectionScreen.swift:7`

**Interfaces:**
- Consumes: the Swift `StreamProbe` public API from `Sources/StreamProbe/StreamProbe.swift` — `init()`, `attach(player:)`, `show()`, `hide()`, `detach()`, `makeOverlayWindow(windowScene:) -> UIWindow`, `var overlayPresenter: OverlayPresenter`. The package product is `StreamProbe` (library) at repo root `Package.swift`.
- Produces: an `iosApp` that builds green against the local SPM package with `STREAMPROBE_LOCAL=1`, no longer referencing `StreamProbe_` or the old XCFramework.

- [ ] **Step 1: Ensure the local Release XCFramework exists**

The local SPM binary target points at the Release XCFramework. Build it if missing (idempotent):
```bash
./gradlew :sdk:assembleStreamProbeCoreReleaseXCFramework --no-daemon
ls -d sdk/build/XCFrameworks/release/StreamProbeCore.xcframework
```
Expected: the `.xcframework` directory exists.

- [ ] **Step 2: Repoint `iosApp/project.yml` to the local SPM package**

Replace the framework dependency (lines 22-25) and add a top-level `packages:` block. Change:
```yaml
    dependencies:
      - framework: ../sdk/build/XCFrameworks/debug/StreamProbe.xcframework
        embed: true
        codeSign: false
```
to:
```yaml
    dependencies:
      - package: StreamProbe
        product: StreamProbe
```
Then add a `packages:` section. Insert it immediately after the `options:` block (after line 7, before `targets:` on line 9):
```yaml
packages:
  StreamProbe:
    path: ..
```
(`path: ..` is relative to `project.yml`'s directory `iosApp/`, i.e. the repo root where `Package.swift` lives. The binary target inside the package resolves locally because `STREAMPROBE_LOCAL=1` is set for the build.)

- [ ] **Step 3: Migrate `AppDependencies.swift` to the Swift `StreamProbe`**

In `iosApp/iosApp/App/AppDependencies.swift`, change line 11:
```swift
    let probe = StreamProbe_()
```
to:
```swift
    let probe = StreamProbe()
```
Also update the now-stale doc comment on line 5 ("Owns the single `StreamProbe_`…") to drop the trailing underscore:
```swift
/// Process-wide dependency holder. Owns the single `StreamProbe` (shared by the player and the
```

- [ ] **Step 4: Migrate the three screen views to the Swift `StreamProbe`**

These declare `let probe: StreamProbe_`. Change each to `let probe: StreamProbe`:
- `iosApp/iosApp/Player/PlayerScreen.swift:10` — `    let probe: StreamProbe_` → `    let probe: StreamProbe`
- `iosApp/iosApp/Settings/SettingsScreen.swift:9` — `    let probe: StreamProbe_` → `    let probe: StreamProbe`
- `iosApp/iosApp/Streams/StreamSelectionScreen.swift:7` — `    let probe: StreamProbe_` → `    let probe: StreamProbe`

(`attach(player:)`/`show()`/`detach()` call sites in `PlayerScreen.start()/stop()` are unchanged — the Swift API mirrors the Kotlin one.)

- [ ] **Step 5: Rewrite `SceneDelegate` to use the packaged overlay installer**

The Swift overlay types (`StreamProbeOverlayWindow`, `OverlayHostViewController`) now live inside the package and are installed via `makeOverlayWindow(windowScene:)`. In `iosApp/iosApp/App/SceneDelegate.swift`, replace the manual window construction (lines 8 + 15-25). Change the property declaration on line 8:
```swift
    var overlayWindow: StreamProbeOverlayWindow?   // strong ref — a detached UIWindow deallocates
```
to:
```swift
    var overlayWindow: UIWindow?   // strong ref — a detached UIWindow deallocates
```
and replace the body block (lines 15-25):
```swift
        let deps = AppDependencies.shared
        let overlay = StreamProbeOverlayWindow(windowScene: windowScene)
        overlay.windowLevel = .alert + 1
        overlay.backgroundColor = .clear
        let host = OverlayHostViewController(presenter: deps.probe.overlayPresenter)
        host.view.backgroundColor = .clear
        overlay.rootViewController = host
        overlay.isHidden = false
        overlayWindow = overlay

        deps.registerOverlayWindow(overlay)
```
with:
```swift
        let deps = AppDependencies.shared
        let overlay = deps.probe.makeOverlayWindow(windowScene: windowScene)
        overlayWindow = overlay

        deps.registerOverlayWindow(overlay)
```
(`registerOverlayWindow(_:)` and the `settings.$overlayVisible` → `isHidden` binding in `AppDependencies` are unchanged — they still drive the Settings toggle.)

- [ ] **Step 6: Regenerate the Xcode project and build the demo**

```bash
cd iosApp && xcodegen generate && cd ..
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build/spm \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -20
```
Expected: **BUILD SUCCEEDED**. The demo links `StreamProbeCore` via the local SPM package and uses the Swift `StreamProbe` + packaged overlay. (Both the Swift `StreamProbe` and the Kotlin `StreamProbe_` exist in the package at this point — distinct names — so the build is green before the Task 2 cleanup. A `Locale.languageCode`/AVFoundation deprecation warning from the package is expected.)

- [ ] **Step 7: Run the demo's unit + UI tests**

These require a booted simulator; pick one from `xcrun simctl list devices available`. The UI flow `test_settings_opensAndTogglesOverlay` needs no network (it navigates + toggles only); the unit tests use the `PlayerEngine` mock (no real player). Live playback is **not** exercised here.
```bash
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath iosApp/build/spm \
  CODE_SIGNING_ALLOWED=NO test 2>&1 | tail -30
```
Expected: **TEST SUCCEEDED**. If no matching simulator name exists, substitute an available one. If the simulator cannot boot in this environment, record that the build passed and defer the test run to the manual follow-up.

- [ ] **Step 8: Commit**

```bash
git add iosApp/project.yml iosApp/iosApp.xcodeproj iosApp/iosApp/App/AppDependencies.swift \
  iosApp/iosApp/App/SceneDelegate.swift iosApp/iosApp/Player/PlayerScreen.swift \
  iosApp/iosApp/Settings/SettingsScreen.swift iosApp/iosApp/Streams/StreamSelectionScreen.swift
git commit -m "feat(ios): migrate demo to local SPM package + Swift StreamProbe

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Remove the superseded Kotlin/Native iOS probe + `expect/actual StreamProbe`

**Files:**
- Delete: `sdk/src/commonMain/kotlin/com/streamprobe/sdk/StreamProbe.kt`
- Delete: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/StreamProbe.ios.kt`
- Delete: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVPlayerProbe.kt`
- Delete: `sdk/src/iosTest/kotlin/com/streamprobe/sdk/AVPlayerProbePocTest.kt`
- Modify: `sdk/src/androidMain/kotlin/com/streamprobe/sdk/StreamProbe.kt:31,83`
- Modify: `sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVMetricMappers.kt:7-18,93-141`
- Modify: `sdk/build.gradle.kts:23-26`

**Interfaces:**
- Consumes: nothing new. The Swift `StreamProbe` (Task 1) is now the iOS entry point; the iOS mapping math is the Swift `AVTrackMapping.swift` (Plan 2c) over the public primitive helpers in `AVMetricMappers.kt` (kept).
- Produces: an SDK with **no** `expect/actual StreamProbe` class (only `displayLanguage` remains an `expect fun`), the `-Xexpect-actual-classes` flag removed, Android's `StreamProbe` a plain class with identical public API, and `AVMetricMappers.kt` reduced to pure primitive helpers.

- [ ] **Step 1: Delete the four superseded Kotlin files**

```bash
git rm sdk/src/commonMain/kotlin/com/streamprobe/sdk/StreamProbe.kt \
  sdk/src/iosMain/kotlin/com/streamprobe/sdk/StreamProbe.ios.kt \
  sdk/src/iosMain/kotlin/com/streamprobe/sdk/internal/AVPlayerProbe.kt \
  sdk/src/iosTest/kotlin/com/streamprobe/sdk/AVPlayerProbePocTest.kt
```
Rationale: `StreamProbe.kt` is the `expect` class; `StreamProbe.ios.kt` is the iOS `actual` (its lifecycle moved into `ProbeCore` in Plan 1); `AVPlayerProbe.kt` is the Kotlin probe (ported to Swift in Plan 2c); `AVPlayerProbePocTest.kt` is the Phase-3 K/N feasibility PoC whose live leg was always TLS-blocked and whose mapping coverage now lives in `AVMetricMappersTest`/`AVAccessLogMappersTest` (kept). It references `StreamProbe().sessionStore`, which ceases to exist.

- [ ] **Step 2: Make Android's `StreamProbe` a plain class**

In `sdk/src/androidMain/kotlin/com/streamprobe/sdk/StreamProbe.kt`, remove the `actual` modifiers (public API is otherwise byte-identical). Change line 31:
```kotlin
actual class StreamProbe {
```
to:
```kotlin
class StreamProbe {
```
and change the `detach()` declaration (line 83):
```kotlin
    actual fun detach() {
```
to:
```kotlin
    fun detach() {
```

- [ ] **Step 3: Reduce `AVMetricMappers.kt` to pure primitive helpers**

Remove the now-unused AVFoundation/cinterop imports (lines 7-18). Delete these lines:
```kotlin
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAssetVariant
import platform.AVFoundation.AVAssetVariantVideoAttributes
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.extendedLanguageTag
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.presentationSize
import platform.CoreGraphics.CGSize
import platform.Foundation.NSNumber
import platform.Foundation.languageCode
```
(Keep the four model imports on lines 3-6: `AudioTrackInfo`, `SubtitleKind`, `SubtitleTrackInfo`, `VariantInfo`.)

Then delete the AVFoundation-typed functions — everything from the blank line after `defaultSubtitleKind()` to end of file (lines 92/93-141). Delete this entire block:
```kotlin

@OptIn(ExperimentalForeignApi::class)
private fun readPresentationSize(size: CValue<CGSize>): Pair<Int, Int> =
    size.useContents { dimensionOrUnknown(width) to dimensionOrUnknown(height) }

@OptIn(ExperimentalForeignApi::class)
private fun videoCodecs(attributes: AVAssetVariantVideoAttributes): String? =
    joinCodecs(attributes.codecTypes.mapNotNull { (it as? NSNumber)?.let { n -> fourCCToString(n.intValue) } })

/** Maps an `AVAssetVariant` (iOS 15+) to a [VariantInfo]. `isSelected`/`id` are unknown up front. */
@OptIn(ExperimentalForeignApi::class)
internal fun mapVariant(variant: AVAssetVariant): VariantInfo {
    val video = variant.videoAttributes
    val (width, height) = video?.let { readPresentationSize(it.presentationSize) } ?: (-1 to -1)
    return VariantInfo(
        bitrate = pickVariantBitrate(variant.peakBitRate, variant.averageBitRate),
        width = width,
        height = height,
        codecs = video?.let { videoCodecs(it) },
        frameRate = video?.let { frameRateOrUnknown(it.nominalFrameRate) } ?: -1f,
        id = null,
        isSelected = false,
    )
}

/** Maps an audible `AVMediaSelectionOption` to an [AudioTrackInfo]. Channel/sample-rate are unavailable on iOS. */
internal fun mapAudioOption(option: AVMediaSelectionOption): AudioTrackInfo =
    AudioTrackInfo(
        language = preferredLanguageTag(option.extendedLanguageTag, option.locale?.languageCode),
        label = option.displayName.takeIf { it.isNotBlank() },
        codecs = null,
        bitrate = 0,
        channelCount = 0,
        sampleRate = 0,
        isMuxed = false,
        id = null,
        isSelected = false,
    )

/** Maps a legible `AVMediaSelectionOption` to a [SubtitleTrackInfo]. */
internal fun mapLegibleOption(option: AVMediaSelectionOption): SubtitleTrackInfo =
    SubtitleTrackInfo(
        language = preferredLanguageTag(option.extendedLanguageTag, option.locale?.languageCode),
        label = option.displayName.takeIf { it.isNotBlank() },
        mimeType = null,
        kind = defaultSubtitleKind(),
        id = null,
        isSelected = false,
    )
```
The file now ends with `defaultSubtitleKind()` and contains only pure primitive-typed `public` helpers (no `platform.*`/cinterop references, no `@OptIn`). Its hermetic `iosTest` coverage (`AVMetricMappersTest`) is unaffected — it only tests the kept helpers.

- [ ] **Step 4: Drop the `-Xexpect-actual-classes` compiler flag**

In `sdk/build.gradle.kts`, remove the module-level `compilerOptions` block (lines 23-26) that adds the Beta flag — it is no longer needed now that the only expect/actual *class* is gone (`displayLanguage` is an expect *fun*, which needs no flag). Delete:
```kotlin
    compilerOptions {
        // The StreamProbe facade is an expect/actual class (still Beta in Kotlin 2.3); opt in explicitly.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

```
so `kotlin {` is immediately followed by the `android {` block. (Leave the `android { compilerOptions { jvmTarget … } }` block untouched.)

- [ ] **Step 5: Rebuild the Release XCFramework and re-verify the demo still builds**

The XCFramework no longer exports `StreamProbe_`; the demo (Task 1) already uses the Swift `StreamProbe`, so it stays green.
```bash
./gradlew :sdk:assembleStreamProbeCoreReleaseXCFramework --no-daemon
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build/spm \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -20
```
Expected: both **BUILD SUCCEEDED**. Also re-run the standalone package smoke build to confirm the Swift layer still compiles over the rebuilt Core:
```bash
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke build 2>&1 | grep -E "BUILD SUCCEEDED|BUILD FAILED"
```
Expected: **BUILD SUCCEEDED**.

- [ ] **Step 6: Run the full Gradle gate**

```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug 2>&1 | tail -15
```
Expected: **BUILD SUCCESSFUL**. Confirms Android's public API still compiles (plain `StreamProbe`), `androidHostTest`'s `StreamProbeTest` (`StreamProbe()` + `detach()`) passes, the flag removal is clean, and `iosTest` is green after the PoC deletion.

- [ ] **Step 7: Commit**

```bash
git add -A sdk
git commit -m "refactor(ios)!: remove K/N probe + expect/actual StreamProbe, drop -Xexpect-actual-classes

Android StreamProbe becomes a plain class (public API unchanged); iOS entry point
is now the Swift StreamProbe. AVMetricMappers reduced to pure primitive helpers.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: iOS versioning + README SPM section

**Files:**
- Modify: `gradle.properties:16`
- Modify: `README.md:88`

**Interfaces:**
- Consumes: nothing.
- Produces: `IOS_VERSION_NAME=0.1.0` available to `publish-spm.yml` (Task 4); a documented SPM install path.

- [ ] **Step 1: Add `IOS_VERSION_NAME` to `gradle.properties`**

After the `VERSION_NAME=0.5.0` line (line 16), add the independent iOS version:
```properties
VERSION_NAME=0.5.0
# iOS SPM release version (independent of Android's VERSION_NAME); consumed by publish-spm.yml.
IOS_VERSION_NAME=0.1.0
```

- [ ] **Step 2: Add an iOS / Swift Package Manager subsection to the README**

In `README.md`, insert an iOS subsection between the Android code fence (line 88, the closing ```` ``` ````) and the note (line 90). Insert after line 88:
```markdown

### iOS (Swift Package Manager)

Add StreamProbe via Xcode's **File ▸ Add Package Dependencies…** or in your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/oguzhaneksi/StreamProbe.git", from: "0.1.0")
],
targets: [
    .target(
        name: "YourApp",
        dependencies: [.product(name: "StreamProbe", package: "StreamProbe")]
    )
]
```

Then `import StreamProbe` — you get the Swift entry point plus (via `@_exported`) the Core types. iOS is versioned independently of the Android (Maven Central) release.
```

- [ ] **Step 3: Sanity-check Gradle still reads the properties**

```bash
./gradlew -q :sdk:properties --no-daemon | grep -E "^(version|group):"
```
Expected: prints `version: 0.5.0` and `group: io.github.oguzhaneksi` without error (confirms `gradle.properties` still parses; `IOS_VERSION_NAME` is a plain property consumed by CI, not the module version).

- [ ] **Step 4: Commit**

```bash
git add gradle.properties README.md
git commit -m "docs(ios): add IOS_VERSION_NAME=0.1.0 and SPM install instructions

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: SPM release workflow + `publish-sdk.yml` trigger guard

**Files:**
- Create: `.github/workflows/publish-spm.yml`
- Modify: `.github/workflows/publish-sdk.yml:9-11,33-35`

**Interfaces:**
- Consumes: `IOS_VERSION_NAME` (Task 3), the existing `:sdk:zipReleaseXCFramework` Gradle task, the `Package.swift` `url`/`checksum` placeholders.
- Produces: a `workflow_dispatch` pipeline that builds + zips the Release XCFramework, fills `Package.swift`, tags `v<version>`, and drafts a GitHub Release; plus a guard so the Android Maven workflow never fires on iOS releases.

- [ ] **Step 1: Guard `publish-sdk.yml` so it ignores iOS `vX.Y.Z` releases**

The Android workflow triggers on **any** `release: [published, prereleased]`. Publishing the iOS draft release would otherwise re-run the Maven Central publish. Add an `if:` guard to **both** jobs so they run only for Android `release/v*` tags or a manual dispatch.

Change the `validate` job header (lines 9-11):
```yaml
  validate:
    name: Dry-Run Validation
    runs-on: ubuntu-latest
```
to:
```yaml
  validate:
    name: Dry-Run Validation
    if: ${{ github.event_name == 'workflow_dispatch' || startsWith(github.ref, 'refs/tags/release/') }}
    runs-on: ubuntu-latest
```
Change the `publish` job header (lines 33-35):
```yaml
  publish:
    name: Publish to Maven Central
    needs: validate
```
to:
```yaml
  publish:
    name: Publish to Maven Central
    if: ${{ github.event_name == 'workflow_dispatch' || startsWith(github.ref, 'refs/tags/release/') }}
    needs: validate
```
(For a `release` event `github.ref` is `refs/tags/<tag>`: Android = `refs/tags/release/v…` → runs; iOS = `refs/tags/v…` → skipped. `workflow_dispatch` still works for manual Android publishes.)

- [ ] **Step 2: Create `publish-spm.yml`**

Create `.github/workflows/publish-spm.yml`:
```yaml
name: Publish iOS SPM Package

on:
  workflow_dispatch:
    inputs:
      version:
        description: "iOS release version; must equal IOS_VERSION_NAME (e.g. 0.1.0)"
        required: true

permissions:
  contents: write

jobs:
  publish:
    name: Build XCFramework & Draft Release
    runs-on: macos-15
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'zulu'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Assert version matches IOS_VERSION_NAME
        run: |
          EXPECTED=$(grep '^IOS_VERSION_NAME=' gradle.properties | cut -d'=' -f2)
          if [ "${{ github.event.inputs.version }}" != "$EXPECTED" ]; then
            echo "::error::Input version '${{ github.event.inputs.version }}' != IOS_VERSION_NAME '$EXPECTED'"
            exit 1
          fi
          echo "VERSION=$EXPECTED" >> "$GITHUB_ENV"

      - name: Build & zip Release XCFramework
        run: ./gradlew :sdk:zipReleaseXCFramework --no-daemon --stacktrace

      - name: Compute checksum
        id: checksum
        run: |
          ZIP=sdk/build/XCFrameworks/release/StreamProbeCore.xcframework.zip
          CHECKSUM=$(swift package compute-checksum "$ZIP")
          echo "value=$CHECKSUM" >> "$GITHUB_OUTPUT"
          echo "Checksum: $CHECKSUM"

      - name: Write url + checksum into Package.swift
        env:
          CHECKSUM: ${{ steps.checksum.outputs.value }}
        run: |
          URL="https://github.com/${{ github.repository }}/releases/download/v${VERSION}/StreamProbeCore.xcframework.zip"
          python3 - "$URL" "$CHECKSUM" <<'PY'
          import re, sys
          url, checksum = sys.argv[1], sys.argv[2]
          s = open("Package.swift").read()
          s = re.sub(r'(url:\s*")[^"]*(")', lambda m: m.group(1) + url + m.group(2), s, count=1)
          s = re.sub(r'(checksum:\s*")[^"]*(")', lambda m: m.group(1) + checksum + m.group(2), s, count=1)
          open("Package.swift", "w").write(s)
          PY
          echo "---- Package.swift ----"; cat Package.swift

      - name: Verify Package.swift parses
        run: swift package dump-package > /dev/null

      - name: Commit Package.swift
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add Package.swift
          git commit -m "release(spm): v${VERSION}"
          git push origin "HEAD:${{ github.ref_name }}"

      - name: Create and push tag
        run: |
          git tag -a "v${VERSION}" -m "iOS SPM release v${VERSION}"
          git push origin "v${VERSION}"

      - name: Create draft GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "v${VERSION}" \
            sdk/build/XCFrameworks/release/StreamProbeCore.xcframework.zip \
            --draft \
            --title "iOS v${VERSION}" \
            --notes "StreamProbe iOS SPM release v${VERSION}. Install: https://github.com/${{ github.repository }}.git (from: \"${VERSION}\")."
```
Notes for the implementer: the tag captures the `release(spm)` commit (Package.swift with the real url+checksum), so SPM reads the correct manifest at `vX.Y.Z`. The release is a **draft** — its asset is not publicly downloadable until a human reviews and clicks **Publish**, at which point the `v*` tag (not `release/v*`) means the guarded `publish-sdk.yml` stays dormant.

- [ ] **Step 3: Validate the workflow YAML and the manifest**

```bash
python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/publish-spm.yml', '.github/workflows/publish-sdk.yml']]; print('YAML OK')"
STREAMPROBE_LOCAL=1 swift package dump-package > /dev/null && echo "Package.swift parses"
```
Expected: `YAML OK` and `Package.swift parses`. If `actionlint` is installed, also run `actionlint .github/workflows/publish-spm.yml` and expect no errors.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/publish-spm.yml .github/workflows/publish-sdk.yml
git commit -m "ci(ios): add publish-spm.yml; guard publish-sdk.yml to release/v* tags

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **Full Gradle gate green (Android byte-identical, flag removed):**
```bash
./gradlew :sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeCoreDebugXCFramework \
  :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck \
  :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug
```
Expected: **BUILD SUCCESSFUL**.

- [ ] **SPM package smoke build green:**
```bash
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke build
```
Expected: **BUILD SUCCEEDED**.

- [ ] **Demo builds via the local SPM package (no XCFramework path, no `StreamProbe_`):**
```bash
cd iosApp && xcodegen generate && cd ..
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build/spm \
  CODE_SIGNING_ALLOWED=NO build
```
Expected: **BUILD SUCCEEDED**. (Demo unit + UI tests pass on a booted simulator — settings-toggle flow needs no network.)

- [ ] **Workflow + manifest valid:** `publish-spm.yml`/`publish-sdk.yml` parse as YAML; `swift package dump-package` succeeds.

- [ ] **Manual follow-ups (outside this sandbox):**
  1. **Live overlay verification (required by the design spec):** run the demo on a device/simulator with working networking against a real HLS stream; confirm the overlay renders live variants/segments/switches/errors. Simulator TLS is sandbox-blocked here.
  2. **Release dry run:** trigger `publish-spm.yml` (workflow_dispatch, version `0.1.0`) on a clean macOS runner; confirm it produces a draft release with `StreamProbeCore.xcframework.zip` and a `Package.swift` whose `checksum` matches; then verify a throwaway consumer resolves `from: "0.1.0"` after the draft is published.

---

## Notes / Carryovers

- **SPM target renamed during execution:** the Swift SPM target was renamed `StreamProbeIOS` → `StreamProbe` so consumers write `import StreamProbe`; `Sources/` path updated to `Sources/StreamProbe/` accordingly. `StreamProbeCore` (the binary framework name) is unchanged.
- **Deferred (intentional):** annotating the Swift `StreamProbe` `@MainActor` (Plan 2c carryover) remains a separate strict-concurrency pass — out of scope here.
- **`displayLanguage` stays** an `expect fun` (the only remaining K/N cinterop in `iosMain`, via `NSLocale`) — not affected by dropping `-Xexpect-actual-classes`.
- This is the final plan of the iOS two-layer / SPM migration; after it, `commonMain` is the shared brain, the iOS platform layer is Swift, and iOS ships SPM-consumable with the full overlay.
