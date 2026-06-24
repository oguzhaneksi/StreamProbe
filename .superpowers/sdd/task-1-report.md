## Task 1 Report: Migrate iOS demo to local SPM package + Swift StreamProbe

### Status: DONE

### What Was Changed

**Brief's steps, plus two additional fixes required:**

#### Step 1 — Release XCFramework
Already existed at `sdk/build/XCFrameworks/release/StreamProbeCore.xcframework`. No build needed.

#### Step 2 — `iosApp/project.yml`
- Replaced `dependencies: - framework: ../sdk/build/XCFrameworks/debug/StreamProbe.xcframework` with `- package: StreamProbe / product: StreamProbe`.
- Added `packages: StreamProbe: path: ..` block after `options:`.

#### Step 3 — `iosApp/iosApp/App/AppDependencies.swift`
- Doc comment: `StreamProbe_` → `StreamProbe`.
- Instantiation: `StreamProbe_()` → `StreamProbe()`.

#### Step 4 — Three screen views
- `iosApp/iosApp/Player/PlayerScreen.swift:10`: `StreamProbe_` → `StreamProbe`
- `iosApp/iosApp/Settings/SettingsScreen.swift:9`: `StreamProbe_` → `StreamProbe`
- `iosApp/iosApp/Streams/StreamSelectionScreen.swift:7`: `StreamProbe_` → `StreamProbe`

#### Step 5 — `iosApp/iosApp/App/SceneDelegate.swift`
- `overlayWindow: StreamProbeOverlayWindow?` → `overlayWindow: UIWindow?`
- Replaced 9-line manual overlay construction with `deps.probe.makeOverlayWindow(windowScene:)`.

#### ADDITIONAL FIX 1 — Package.swift / Sources/ rename (not in brief, but required)
The brief assumed `import StreamProbe` would resolve from a package product named `StreamProbe` backed by target `StreamProbeIOS`. In Swift Package Manager the module name is the **target name**, not the product name. So `import StreamProbe` would fail with "unable to resolve module dependency: 'StreamProbe'" — the module was built as `StreamProbeIOS`.

Fix: rename the Package.swift target from `StreamProbeIOS` → `StreamProbe`, rename `Sources/StreamProbeIOS/` → `Sources/StreamProbe/`, and update the product's `targets` array accordingly. This is a Swift-layer change (not Kotlin/SDK), directly enabling the iosApp build.

#### ADDITIONAL FIX 2 — pbxproj `XCSwiftPackageProductDependency` missing `package` key
XcodeGen 2.45.4 generates the `XCSwiftPackageProductDependency` entry without the `package` reference key back to the `XCLocalSwiftPackageReference`. Without this key, Xcode's explicit-module build system cannot resolve the module even when the product is in the build graph. Fix: manually added `package = 7FC3F36957E70F74790F440D /* XCLocalSwiftPackageReference ".." */;` to the `8046CB809E50B3A878B8E576` product dependency entry in `project.pbxproj`.

### Build Command + Tail Output

```
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build/spm \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -10
```

Tail:
```
Validate .../Debug-iphonesimulator/iosApp.app
Touch .../Debug-iphonesimulator/iosApp.app

** BUILD SUCCEEDED **
```

### Test Command + Tail Output

```
STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,id=4B73D597-83C2-4638-9519-271B71AB85E2' \
  -derivedDataPath iosApp/build/spm CODE_SIGNING_ALLOWED=NO test 2>&1 | tail -10
```

Simulator: iPhone 16 (OS 18.x). Tail:
```
Test Suite 'All tests' passed at 2026-06-23 13:56:41.763.
   Executed 3 tests, with 0 failures (0 unexpected) in 24.295 (24.299) seconds

** TEST SUCCEEDED **
```
All 3 tests passed (unit + UI including `test_settings_opensAndTogglesOverlay`).

### Files Changed

| File | Change |
|---|---|
| `Package.swift` | Target renamed `StreamProbeIOS` → `StreamProbe`; product targets updated |
| `Sources/StreamProbeIOS/` → `Sources/StreamProbe/` | Directory renamed (git rename, 19 files) |
| `iosApp/project.yml` | Framework dep → package dep; `packages:` block added |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Added `package` key to `XCSwiftPackageProductDependency` |
| `iosApp/iosApp/App/AppDependencies.swift` | `StreamProbe_()` → `StreamProbe()`; doc comment fixed |
| `iosApp/iosApp/App/SceneDelegate.swift` | `overlayWindow` type simplified; manual overlay wiring → `makeOverlayWindow(windowScene:)` |
| `iosApp/iosApp/Player/PlayerScreen.swift` | `let probe: StreamProbe_` → `let probe: StreamProbe` |
| `iosApp/iosApp/Settings/SettingsScreen.swift` | `let probe: StreamProbe_` → `let probe: StreamProbe` |
| `iosApp/iosApp/Streams/StreamSelectionScreen.swift` | `let probe: StreamProbe_` → `let probe: StreamProbe` |

### Commit

`f5907b9` — feat(ios): migrate demo to local SPM package + Swift StreamProbe

### Self-Review Findings

1. **XcodeGen `package` key regression**: XcodeGen 2.45.4 omits the required `package` key from `XCSwiftPackageProductDependency` for local packages. If anyone runs `xcodegen generate` manually without also re-patching the pbxproj, the build will break again. A follow-up could add a post-gen script or document this in a CLAUDE.md / README. Alternatively check if a newer XcodeGen version fixes this.

2. **`StreamProbeIOS.swift` naming**: After renaming the target to `StreamProbe`, the file `Sources/StreamProbe/StreamProbeIOS.swift` still exists with the old name and `@_exported import StreamProbeCore`. This is benign (it works correctly) but the filename is now misleading. A follow-up cleanup could rename it (e.g., to `StreamProbeReexport.swift`).

3. **Both Swift `StreamProbe` and Kotlin `StreamProbe_` exist**: As expected per the task brief, both coexist in the package at this point. The Kotlin one is removed in Task 2.

### Concerns

**MINOR**: The pbxproj manual `package` key patch must be re-applied every time `xcodegen generate` is run. This could surprise future contributors. Recommend either filing an XcodeGen issue, adding a post-gen hook, or documenting it clearly.

---

## Fix wave 1

### Status: DONE

### XcodeGen Bug Reproduction

**Bug confirmed.** Running `xcodegen generate` (v2.45.4) in `iosApp/` produces a `project.pbxproj` where the `XCSwiftPackageProductDependency` block for `productName = StreamProbe` is missing the `package = <ref-id>` key:

```
/* Begin XCSwiftPackageProductDependency section */
    8046CB809E50B3A878B8E576 /* StreamProbe */ = {
        isa = XCSwiftPackageProductDependency;
        productName = StreamProbe;     ← package key absent
    };
/* End XCSwiftPackageProductDependency section */
```

The `XCLocalSwiftPackageReference` entry is still generated correctly with id `7FC3F36957E70F74790F440D`. The script derives this id dynamically (no hardcoded object IDs) and re-injects the `package =` line.

### What Was Changed

| File | Change |
|---|---|
| `iosApp/generate.sh` | New executable wrapper: runs `xcodegen generate`, then Python3 re-injects the missing `package =` key into `XCSwiftPackageProductDependency` for `productName = StreamProbe`; idempotent if key already present |
| `CLAUDE.md` | Added 2-line note after the full CI gate block: regenerate with `iosApp/generate.sh`, not bare `xcodegen generate`, because XcodeGen 2.45.4 drops local-package linkage |
| `Sources/StreamProbe/StreamProbeIOS.swift` → `Sources/StreamProbe/CoreReexport.swift` | `git mv` rename; in-file comment was already accurate (no old module name reference) |
| `iosApp/iosApp/Overlay/` | Empty directory removed with `rmdir`; was untracked, no git change |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Re-generated by the script (and patched) — committed so repo stays consistent |

### Build 1: Regen-safe proof

Command:
```
bash iosApp/generate.sh && STREAMPROBE_LOCAL=1 xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/build/spm CODE_SIGNING_ALLOWED=NO build
```

Tail output:
```
note: Removed stale file '.../StreamProbeIOS.o'
note: Removed stale file '.../StreamProbeIOS.swiftconstvalues'
...
** BUILD SUCCEEDED **
```

### Build 2: Package still compiles after rename

Command:
```
STREAMPROBE_LOCAL=1 xcodebuild -scheme StreamProbe -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath sdk/build/spm-smoke build 2>&1 | grep -E "BUILD SUCCEEDED|BUILD FAILED"
```

Output:
```
** BUILD SUCCEEDED **
```
