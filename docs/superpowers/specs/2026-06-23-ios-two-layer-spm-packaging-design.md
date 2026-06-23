# iOS Two-Layer Architecture + SPM Distribution — Design Spec

> **Status:** Design approved 2026-06-23. Implementation plan pending.
> **Supersedes:** the original migration-roadmap sub-task **6.4** ("XCFramework + checked-in `Package.swift`, binary-only"). This redefines the iOS adapter established in Phases 3–4 and is large enough to be its own phase (**Phase 7**), with SPM distribution as its natural output rather than a standalone packaging step.

## Context & Motivation

StreamProbe's iOS SDK today is a single Kotlin/Native layer: `iosMain` contains the AVFoundation probe (`AVPlayerProbe`), the access-log/metric mappers, and the `StreamProbe` facade, all built into one static XCFramework. The overlay UI, by contrast, lives entirely in the **demo app** (`iosApp/iosApp/Overlay/`, 15 Swift files) and is *not* part of the SDK — so an external consumer would get the Kotlin core + `OverlayPresenter` but **no rendered overlay**, breaking parity with Android (whose overlay Views ship inside the SDK).

Two problems motivate this work:

1. **Distribution gap (the original 6.4):** there is no `Package.swift`; iOS is not consumable via SPM.
2. **Architecture/DX gap (raised during brainstorming):** the Kotlin/Native AVFoundation cinterop is the least debuggable part of the codebase (member-vs-category property rules, `CValue<CGSize>`, null-guarded `String?` constants — see `sdk/src/iosMain/CLAUDE.md` and the Phase 3 findings). And the overlay UI isn't packaged at all.

The decision is to restructure iOS into the **mainstream "KMP for shared logic, native for platform glue + UI"** architecture: a shared Kotlin **Core** binary plus a native **Swift** layer that owns all AVFoundation I/O and the overlay UI. This was always a sanctioned fallback (D9/D11 explicitly allow "a thin Swift shim into the Kotlin `SessionStore`"); the Kotlin/Native probe served its purpose as the Phase-3 feasibility proof, and we now optimize for production debuggability and consumer parity.

## Goals

- Ship iOS as an SPM-consumable package that gives external consumers the **full draggable overlay out of the box** (Android parity).
- Move all iOS-specific AVFoundation observation and UI into a **debuggable Swift layer** (`Sources/`).
- Keep `commonMain` the pure, portable shared brain; confine iOS-only Kotlin (facade + mapping helpers) to `iosMain`, leaving the `NSLocale` `displayLanguage` actual as the only remaining K/N cinterop.
- Preserve Android's published public API **byte-identical** and its Maven Central flow **untouched**.

## Non-Goals (out of scope)

- FairPlay DRM (migration sub-task 6.1 — gated on external license infra).
- Any change to Android internals, the Maven Central publish flow, or `VERSION_NAME` semantics for Android.
- CocoaPods distribution.
- macOS / tvOS / watchOS targets (iOS only).

---

## Target Architecture

```
┌─ StreamProbeCore  (Kotlin binary XCFramework — shared with Android) ──────────┐
│  commonMain (the genuinely-shared brain — UNCHANGED in spirit):               │
│    • SessionStore (internal), 17 models, OverlayPresenter, formatters,         │
│      parsers, registries, StallDiagnostics (M12), displayLanguage (expect fun) │
│  androidMain:  StreamProbe (plain class, same public API), PlayerInterceptor,  │
│                overlay Views   ← UNCHANGED                                      │
│  iosMain (iOS-only Kotlin, exported to the framework):                         │
│    • LanguageNames.ios.kt (NSLocale actual — the only K/N cinterop)            │
│    • NEW pure mapping helpers (primitive-typed): pickVariantBitrate,           │
│      segmentThroughput, switch-from-bitrate-delta detection, etc.              │
│    • NEW narrow public facade: ProbeCore holder + DiagnosticsSink interface    │
│  framework baseName: "StreamProbeCore"                                          │
└────────────────────────────────────────────────────────────────────────────────┘
                              ▲ depends on (SPM binary target)
┌─ StreamProbeIOS  (Swift source target, Sources/StreamProbeIOS — NEW) ─────────┐
│  StreamProbe.swift      iOS entry: attach(AVPlayer), show()/hide(), detach(),  │
│                         + overlay-window installer                             │
│  AVPlayerProbe.swift    AVFoundation observation (access/error-log             │
│                         notifications, KVO, AVAssetVariant discovery) →        │
│                         extract primitives → Core pure mappers → Core sink     │
│  Overlay/               the 15 Swift UI files + StreamProbeOverlayWindow       │
│  @_exported import StreamProbeCore  (consumers `import StreamProbe` only)       │
└────────────────────────────────────────────────────────────────────────────────┘
```

### Component responsibilities & interfaces

**`StreamProbeCore` (the Kotlin binary XCFramework).** Bundles the shared `commonMain` brain plus a thin iOS-only `iosMain` Kotlin layer; both are exported to the framework.

- *What it does:* aggregates diagnostics (`SessionStore`), turns them into a render-ready `OverlayViewState` (`OverlayPresenter`), and provides pure model-mapping math for the iOS probe.
- *How you use it (from Swift):* construct the Kotlin holder; write diagnostics through its narrow **sink**; observe `holder.presenter.viewState` (SKIE async-sequence); drive presenter intents (`onChipSelected`, `onCollapseToggled`, …).
- *Where things live & why:* the genuinely-shared types (`SessionStore`, models, `OverlayPresenter`, formatters, parsers, registries) stay in **commonMain** — they are the only code Android also uses. The facade and the mapping helpers are **iOS-only logic consumed only by the Swift layer**, so they live in **iosMain**, next to their only consumer. This is legal and clean because in a KMP module all source sets compile into **one module** — `internal` visibility spans the whole module, so `iosMain` can call `SessionStore`'s `internal` write methods directly and re-expose only a narrow `public` surface to Swift, with no need to widen anything in commonMain.
- *Public surface (new/changed), all in `iosMain`:*
  - A Kotlin holder class (working name **`ProbeCore`** — distinct from the `StreamProbeCore` framework/module name) bundling the `internal` `SessionStore`, the public `OverlayPresenter`, and the show/hide coroutine-scope lifecycle (moved out of the old `StreamProbe.ios.kt`).
  - A narrow **write-sink** interface (working name **`DiagnosticsSink`**) exposing only the writes the probe needs: `updateTrackList`, `updateActiveTrack`, `updateActiveAudioTrack`, `updateActiveSubtitleTrack`, `addSegmentMetric`, `addTrackSwitchEvent`, `addPlaybackError`, `addDrmSessionEvent`, `updateDrmState`, `clearPlaybackErrors`, `clear`. `SessionStore` stays `internal` behind it.
  - The pure mapping helpers become `public`, refactored to take **primitives** (no AVFoundation types). They stay in `iosMain` and keep their existing hermetic `iosTest` coverage (`AVMetricMappersTest`, `AVAccessLogMappersTest`) — zero test migration.
  - The 17 model types stay `public` in commonMain (already are — SKIE already exposes them to the demo).
- *Depends on:* commonMain is portable; the only K/N cinterop remaining in `iosMain` is the `NSLocale` `displayLanguage` actual (the mapping helpers are now AVFoundation-free).

**`StreamProbeIOS` (Swift, source).** All iOS platform glue + UI.

- *What it does:* observes AVFoundation, feeds Core, and renders the overlay.
- *How you use it:* `let probe = StreamProbe(); probe.attach(player:); probe.show()` — then install the overlay window (a one-line installer the layer provides). API mirrors today's Kotlin `StreamProbe.ios.kt`.
- *Interface to Core:* writes via the Core sink; reads `core.presenter.viewState`. AVFoundation field extraction is trivial Swift; the real mapping math is delegated to Core's pure helpers.
- *Depends on:* the `StreamProbeCore` binary target (+ AVFoundation, UIKit).

### `expect/actual` cleanup (by-product)

Removing the `expect/actual StreamProbe` **class** eliminates the **only expect/actual class** in the project (`displayLanguage` remains an expect *fun*, which is stable). Consequence: the Beta **`-Xexpect-actual-classes` compiler flag is dropped**. iOS's entry point becomes the Swift `StreamProbe`; Android defines its own plain `StreamProbe` class in `androidMain` with the **same public API** (its `detach()` changes from `actual fun` to a regular `fun` — no consumer-visible change).

---

## Data Flow (iOS, after re-architecture)

```
AVPlayer ─ NSNotificationCenter / KVO / AVAssetVariant   (Swift: AVPlayerProbe.swift)
   → extract primitive fields (Swift)
   → StreamProbeCore pure mappers (Kotlin)   → SDK models
   → StreamProbeCore write sink (Kotlin)      → SessionStore
   → OverlayPresenter (Kotlin)                → StateFlow<OverlayViewState>
   ─ (SKIE async-sequence) ──→ OverlayHostViewController (Swift)
   → OverlayPanelView (Swift/UIKit)
   → StreamProbeOverlayWindow (Swift, separate root UIWindow at .alert+1)
```

Only the **observation + extraction** legs move to Swift; the **store, presenter, and pure mapping math** remain Kotlin in the `StreamProbeCore` binary (store/presenter shared via commonMain; the iOS mapping math in iosMain).

---

## Distribution & Release

### `Package.swift` (checked in at repo root)

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "StreamProbe",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "StreamProbe", targets: ["StreamProbeIOS"]),
    ],
    targets: [
        .binaryTarget(
            name: "StreamProbeCore",
            url: "https://github.com/oguzhaneksi/StreamProbe/releases/download/<version>/StreamProbeCore.xcframework.zip",
            checksum: "<sha256>"
        ),
        .target(
            name: "StreamProbeIOS",
            dependencies: ["StreamProbeCore"],
            path: "Sources/StreamProbeIOS"
        ),
    ]
)
```

- Consumers add `.package(url: "https://github.com/oguzhaneksi/StreamProbe.git", from: "x.y.z")` and `import StreamProbe`.
- Binary = the **Release** XCFramework (`assembleStreamProbeReleaseXCFramework`), **static**, baseName `StreamProbeCore`.
- Zipped with `ditto -c -k --keepParent StreamProbeCore.xcframework StreamProbeCore.xcframework.zip`; checksum via `swift package compute-checksum`.
- A Gradle helper task (`zipReleaseXCFramework`) builds + zips so local and CI are byte-identical.
- `Package.swift` at `main` between releases reflects the **last shipped** version (standard for binary SPM; consumers pin to a tag, and SPM reads `Package.swift` *at that tag*).

### Versioning (Android/iOS separated)

| Platform | Version source | Git tag | Channel |
|---|---|---|---|
| Android | `VERSION_NAME` (`gradle.properties`) | `release/vX.Y.Z` (existing) | Maven Central (`publish-sdk.yml`, **unchanged**) |
| iOS | **new `IOS_VERSION_NAME`** (`gradle.properties`) | `vX.Y.Z` (plain) | GitHub Release zip (`publish-spm.yml`) |

Rationale: **SPM only recognizes plain-semver tags** (`X.Y.Z` / `vX.Y.Z`) and ignores path-prefixed tags like `release/v...`. The two tag namespaces therefore never collide, and SPM only ever sees iOS releases. Independent versions also fit the independent distribution channels and allow platform-only fixes. (Trade-off: a shared `commonMain` change warrants bumping both.)

### Release CI — `publish-spm.yml` (new, separate)

A **`workflow_dispatch`** workflow on a **macOS runner**, input `version`:

1. Assert `version` == `IOS_VERSION_NAME` in `gradle.properties` (fail on mismatch).
2. Build Release XCFramework → zip (`ditto`) → checksum (`swift package compute-checksum`).
3. Write `url` + `checksum` into `Package.swift`; commit `release(spm): <version>`; push.
4. Create annotated tag `v<version>`; push.
5. Create a **draft** GitHub Release for the tag with `StreamProbeCore.xcframework.zip` attached.

**Release runbook:** bump `IOS_VERSION_NAME` → run `publish-spm.yml` → review the draft, add notes, click **Publish**. The human-published release leaves the existing `publish-sdk.yml` (Maven, `on: release`, tag `release/v*`) untouched. The draft step gives a review gate and sidesteps cross-workflow trigger limitations (no PAT needed).

### Demo app (`iosApp/`)

The demo **consumes `StreamProbeIOS` as a local SPM dependency** (XcodeGen `packages:` with a local path) and **deletes its 15-file overlay copy**, using the new Swift probe + packaged overlay. Single source of truth; dogfoods the package. (Today it references a local debug XCFramework via `project.yml` and carries its own `Overlay/` — both are replaced.)

---

## Testing

- **Core (Kotlin):** the iOS mapping helpers keep their existing hermetic coverage in `iosTest` (`AVMetricMappersTest`, `AVAccessLogMappersTest`), now primitive-typed — no test migration. `OverlayPresenterTest` (commonTest) unchanged. The narrow sink/`ProbeCore` facade gets `iosTest` unit coverage.
- **StreamProbeIOS (Swift):** XCTest for the Swift probe's extraction + observation logic (feasible now that it's native Swift with injectable AVFoundation seams) and any Swift-side helpers. The existing demo XCTest/XCUITest suites continue to pass against the repackaged app.
- **Live verification (required):** the rewritten `AVPlayerProbe.swift` must be verified against a real stream on a **simulator/device** — the Kotlin probe's live leg was blocked by the simulator-TLS sandbox, so this path was never end-to-end verified.
- **SPM consumability:** verify SKIE-bridged Core types (flows, `onEnum` sealed dispatch) resolve and compile from a **source** SPM target consuming the **binary** target; `Package.swift` parses (`swift package dump-package`).

## Error Handling & Edge Cases

- Swift probe must guard the same AVFoundation nullability the Kotlin probe did (nullable media-characteristic constants, missing access-log fields) and degrade gracefully (`isEstimated`/null), preserving current model semantics.
- The Core sink is the single writer; `clear()` on `attach()` preserved.
- Overlay window hit-test passthrough (`.alert+1`, background touches → `nil`) preserved in the packaged window.

## Risks

1. **Rewriting the proven Phase-3 probe.** *Mitigation:* the tested pure mapping math stays in Kotlin; only the observation/KVO/notification plumbing (the hard-to-debug, never-fully-live-tested part) is rewritten in Swift — exactly the part that benefits most from native debuggability. Gate on live verification.
2. **SKIE across the binary→source SPM boundary.** Strong prior evidence (the demo already consumes SKIE types from a local XCFramework), but verify explicitly with a Release binary + source target before cutting a release.
3. **Android public-API drift** from removing `expect/actual StreamProbe`. *Mitigation:* keep the `androidMain` `StreamProbe` API identical; verify with the existing Android gate.
4. **Framework rename** (`StreamProbe` → `StreamProbeCore` baseName) touches the demo's framework reference and any import sites. *Mitigation:* the demo moves to the SPM package anyway; sweep imports.

## Verification (exit gate)

- `Package.swift` resolves from a throwaway consumer; `import StreamProbe` exposes the Swift entry point + (via `@_exported`) Core types; the overlay renders against a live `AVPlayer`.
- `iosApp/` builds via the local SPM package (no XCFramework path, no `Overlay/` copy) and its XCTest + XCUITest suites pass.
- Full existing gate green (Android byte-identical, `-Xexpect-actual-classes` removed): `:sdk:iosSimulatorArm64Test :sdk:assembleStreamProbeReleaseXCFramework :sdk:assembleAndroidMain :sdk:testAndroidHostTest :sdk:lint :sdk:ktlintCheck :sdk:detektAndroidMain :sdk:detektAndroidHostTest :sdk:detektMetadataMain :app:assembleDebug`.
- `publish-spm.yml` produces a draft release with the zip asset and a `Package.swift` whose checksum matches.
