# iOS Overlay Android-Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the `iosApp/iosApp/Overlay/` UIKit overlay to be pixel-for-pixel identical to the Android `androidMain` overlay (colors, typography, layout, all five list-cell types, expandable error rows, landscape split layout, drag-from-header, auto-scroll, orientation rebuild, share).

**Architecture:** All work is host-app side (`iosApp`); no SDK/KMP changes. The overlay observes the existing common `OverlayPresenter` (exposed via SKIE) and renders its `OverlayViewState`. Views are built programmatically in UIKit (no Storyboards/Xibs), mirroring Android's programmatic-view approach. The Kotlin formatter objects (`OverlayFormatters`, `DrmFormatters`) are `internal` and not visible to Swift, so a Swift-side `OverlayFormattersSwift` ports their logic line-by-line.

**Tech Stack:** Swift, UIKit, AutoLayout (programmatic), the `StreamProbe.xcframework` (Kotlin/Native + SKIE), Xcode 26.5.

---

## Verification Strategy (read first)

This project has **no XCTest target**, and the goal is visual parity. Verification per task is therefore **compile-driven**: each task ends by building the app and confirming a clean compile. A final task (Task 16) does **visual verification** in the simulator against the Android reference.

The pure `OverlayFormattersSwift` (Task 3) is a line-by-line port of the Kotlin `OverlayFormatters`/`DrmFormatters`, which are already unit-tested in `commonTest`. Its correctness is confirmed by (a) clean compile and (b) the rendered values matching the Android screenshots in Task 16.

**Canonical build command** (used as the "green check" in every task):

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

Expected on success: `** BUILD SUCCEEDED **`.

**Reference facts (Android → iOS mapping):**
- 1 Android `dp` == 1 iOS `pt` (treat all spec dp values as pt directly).
- Android `letterSpacing` is in em; iOS `NSAttributedString.kern` is absolute pt → `kern = letterSpacing_em × fontSize`. (e.g. 10pt label at 0.1em → kern `1.0`; 13pt title at 0.04em → kern `0.52`.)
- Font weights: Android "sans-serif-medium NORMAL" → `.medium`; Android `BOLD`/medium-bold → `.bold`.
- SKIE enum cases are lowerCamelCase: `CacheStatus.hit/.miss/.stale/.bypass/.unknown`; `ErrorCategory.loadError/.videoCodecError/.droppedFrames/.audioSinkError/.audioCodecError/.drmError`; `DrmScheme.widevine/.playready/.clearkey/.fairplay/.unknown`; `SubtitleKind.sidecar/.cc`; `SwitchReason.initial/.adaptive/.manual/.trickplay/.unknown`; `CdnProvider.cloudflare/.cloudfront/.fastly/.akamai/.unknown`; `DrmSessionState.opening/.opened/.openedWithKeys/.released/.error/.unknown`; `ViewMode.tracks/.segments/.switches/.drm/.errors`.
- SKIE sealed interfaces use `onEnum(of:)`:
  - `OverlayRow` → `.sectionHeader(let h)` (`h.title`), `.video(let r)` (`r.info`), `.audio(let r)` (`r.info`), `.subtitle(let r)` (`r.info`).
  - `TrackSwitchEvent` → `.videoSwitch(let v)`, `.audioSwitch(let a)`, `.subtitleSwitch(let s)`.
  - `DrmSessionEvent` → `.sessionAcquired(let e)`, `.keysLoaded(let e)`, `.sessionError(let e)`, `.sessionReleased(let e)`.
- Kotlin `Int` → Swift `Int32`; Kotlin `Long` → Swift `Int64`. Model fields: `bitrate/width/height/channelCount/sampleRate` are `Int32`; `timestampMs/bufferDurationMs/licenseLatencyMs/sizeBytes/throughputBytesPerSec/totalDurationMs/ttfbMs` are `Int64`.
- Model property names (confirmed against the framework + existing Swift):
  - `SegmentMetric`: `totalDurationMs`, `sizeBytes`, `throughputBytesPerSec`, `uri`, `cdnInfo` (`CdnHeaderInfo`), `networkTiming` (`NetworkTiming?`).
  - `NetworkTiming`: `ttfbMs` (`Int64`), `isEstimated` (`Bool`).
  - `CdnHeaderInfo`: `cdnProvider` (`CdnProvider`), `cacheStatus` (`CacheStatus`), `xCache` (`String?`), `cacheControl` (`String?`), `cdnSpecificHeaders` (`[String: String]`).
  - `VariantInfo`: `width`, `height`, `bitrate` (`Int32`), `codecs` (`String?`), `isSelected` (`Bool`), `id` (`String?`).
  - `AudioTrackInfo`: `label` (`String?`), `language` (`String?`), `codecs` (`String?`), `channelCount` (`Int32`), `bitrate` (`Int32`), `sampleRate` (`Int32`), `isMuxed` (`Bool`), `isSelected` (`Bool`).
  - `SubtitleTrackInfo`: `label` (`String?`), `language` (`String?`), `kind` (`SubtitleKind`), `mimeType` (`String?`), `isSelected` (`Bool`).
  - `ActiveTrackInfo`: `width`, `height`, `bitrate` (`Int32`).
  - `PlaybackErrorEvent`: `category` (`ErrorCategory`), `message` (`String`), `detail` (`String?`), `timestampMs` (`Int64`).
  - `TrackSwitchEvent.*`: `previousTrack`, `newTrack`, `reason` (`SwitchReason`), `bufferDurationMs` (`Int64`), `timestampMs` (`Int64`). Video tracks are `ActiveTrackInfo`; audio/subtitle `newTrack`/`previousTrack` are `AudioTrackInfo`/`SubtitleTrackInfo?`.
  - `DrmSessionEvent.*`: `scheme` (`DrmScheme`), `timestampMs` (`Int64`); `.sessionAcquired` has `state` (`DrmSessionState`); `.keysLoaded` has `licenseLatencyMs` (`Int64`); `.sessionError` has `message` (`String`).
  - `OverlayViewState`: `mode` (`ViewMode`), `isCollapsed` (`Bool`), `stats` (`OverlayStatsState`), `lists` (`OverlayListsState`), `errorIndicator` (`ErrorIndicatorState?`), `isErrorsMode` (`Bool`), `errorsTitle` (`String`).
  - `OverlayStatsState`: `activeTrackText`, `activeAudioText`, `activeSubtitleText`, `latestSegmentText`, `cdnStatusText`, `drmStatusText` (all `String`), `drmVisible` (`Bool`).
  - `OverlayListsState`: `renditionRows` (`[OverlayRow]`), `segments` (`[SegmentMetric]`), `switches` (`[TrackSwitchEvent]`), `drmEvents` (`[DrmSessionEvent]`), `errors` (`[PlaybackErrorEvent]`).
  - `ErrorIndicatorState`: `text`, `contentDescription` (`String`).
  - `OverlayPresenter` intents: `onChipSelected(mode:)`, `onCollapseToggled()`, `onErrorIndicatorTapped()`, `onBackPressed()`, `onClearErrorsClicked()`; observable `viewState` (SKIE async sequence).

---

## File Structure

All paths under `iosApp/iosApp/Overlay/`.

| File | Status | Responsibility |
|------|--------|----------------|
| `OverlayTheme.swift` | Create | `UIColor(argbHex:)` + all color tokens + dot-color factories (mirrors `OverlayDrawables.kt`). *Spec §14 calls this `OverlayDrawables.swift`; renamed to `OverlayTheme` since iOS uses `UIColor`, not `Drawable`.* |
| `OverlayFormattersSwift.swift` | Create | Pure formatters ported from `OverlayFormatters.kt` + `DrmFormatters.kt` |
| `HeaderView.swift` | Create | 44pt header: title, error pill, collapse button |
| `ErrorsHeaderView.swift` | Create | Errors-mode header: ← Back · Errors (N) · Clear · ↗ |
| `RenditionCell.swift` | Create | Tracks tab — section header + video/audio/subtitle item cells |
| `SegmentCell.swift` | Create | Segments tab cell |
| `SwitchCell.swift` | Create | Switches tab cell |
| `ErrorCell.swift` | Create | Errors tab cell (expandable) |
| `DrmCell.swift` | Create | DRM tab cell |
| `StatsView.swift` | Rewrite | Stats section: section labels + value rows |
| `ChipBarView.swift` | Rewrite | Accent filter chips, title-case labels |
| `OverlayPanelView.swift` | Rewrite | Panel chrome + portrait/landscape body assembly |
| `OverlayTableDataSource.swift` | Rewrite | Per-tab cell dispatch, expand state, auto-scroll |
| `OverlayHostViewController.swift` | Rewrite | Drag, orientation, render loop, share |
| `StreamProbeOverlayWindow.swift` | Unchanged | Hit-test pass-through |

Spec reference: `docs/superpowers/specs/2026-06-16-ios-overlay-design.md`.

---

### Task 1: Convert `Overlay/` to a file-system-synchronized group

**Why:** New `.swift` files must be compiled. Converting the `Overlay` group to a `PBXFileSystemSynchronizedRootGroup` (Xcode 16+ default) makes Xcode auto-include every file in the folder — no per-file `project.pbxproj` edits for this plan or future overlay files.

**Files:**
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj`

- [ ] **Step 1: Back up the project file**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe/iosApp
cp iosApp.xcodeproj/project.pbxproj /tmp/project.pbxproj.bak
```

- [ ] **Step 2: Remove the six `PBXBuildFile` entries for Overlay files**

In `iosApp.xcodeproj/project.pbxproj`, delete these six lines from the `PBXBuildFile` section (match by the `/* … in Sources */` comment; GUIDs shown are current):

```
		15F5B46D3D15D9AA7043013F /* OverlayPanelView.swift in Sources */ = {isa = PBXBuildFile; fileRef = BF1A23958C7859C2654EAA0B /* OverlayPanelView.swift */; };
		21BFFA625046939060B83347 /* OverlayHostViewController.swift in Sources */ = {isa = PBXBuildFile; fileRef = 00CC377686B5AC391DF6D3AC /* OverlayHostViewController.swift */; };
		5636038FAFFCD9B09F318260 /* StreamProbeOverlayWindow.swift in Sources */ = {isa = PBXBuildFile; fileRef = 4B0CDDA167603EAD06284B99 /* StreamProbeOverlayWindow.swift */; };
		58F2E37C983B727C62E458B3 /* OverlayTableDataSource.swift in Sources */ = {isa = PBXBuildFile; fileRef = 8E40C83D1C7E41A25D170C1A /* OverlayTableDataSource.swift */; };
		12D3FAF8BAA2775373A8BC46 /* StatsView.swift in Sources */ = {isa = PBXBuildFile; fileRef = 156AA7FE63DC7ED492314667 /* StatsView.swift */; };
		6415A66FFC7F266EFB2CEECA /* ChipBarView.swift in Sources */ = {isa = PBXBuildFile; fileRef = 853B5198A8E8BE62295090B6 /* ChipBarView.swift */; };
```

- [ ] **Step 3: Remove the six `PBXFileReference` entries for Overlay files**

Delete these six lines from the `PBXFileReference` section:

```
		00CC377686B5AC391DF6D3AC /* OverlayHostViewController.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = OverlayHostViewController.swift; sourceTree = "<group>"; };
		4B0CDDA167603EAD06284B99 /* StreamProbeOverlayWindow.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = StreamProbeOverlayWindow.swift; sourceTree = "<group>"; };
		8E40C83D1C7E41A25D170C1A /* OverlayTableDataSource.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = OverlayTableDataSource.swift; sourceTree = "<group>"; };
		BF1A23958C7859C2654EAA0B /* OverlayPanelView.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = OverlayPanelView.swift; sourceTree = "<group>"; };
		156AA7FE63DC7ED492314667 /* StatsView.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = StatsView.swift; sourceTree = "<group>"; };
		853B5198A8E8BE62295090B6 /* ChipBarView.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ChipBarView.swift; sourceTree = "<group>"; };
```

- [ ] **Step 4: Remove the six file entries from the `Sources` build phase**

In the `PBXSourcesBuildPhase` `files = ( … )` list, delete these six lines (leave `AppDelegate`, `PlayerViewController`, `SceneDelegate`):

```
				6415A66FFC7F266EFB2CEECA /* ChipBarView.swift in Sources */,
				21BFFA625046939060B83347 /* OverlayHostViewController.swift in Sources */,
				15F5B46D3D15D9AA7043013F /* OverlayPanelView.swift in Sources */,
				58F2E37C983B727C62E458B3 /* OverlayTableDataSource.swift in Sources */,
				12D3FAF8BAA2775373A8BC46 /* StatsView.swift in Sources */,
				5636038FAFFCD9B09F318260 /* StreamProbeOverlayWindow.swift in Sources */,
```

- [ ] **Step 5: Replace the `Overlay` `PBXGroup` with a synchronized root group**

Find this block:

```
		F6E07FAD03EAD8D773A2D6E8 /* Overlay */ = {
			isa = PBXGroup;
			children = (
				853B5198A8E8BE62295090B6 /* ChipBarView.swift */,
				00CC377686B5AC391DF6D3AC /* OverlayHostViewController.swift */,
				BF1A23958C7859C2654EAA0B /* OverlayPanelView.swift */,
				8E40C83D1C7E41A25D170C1A /* OverlayTableDataSource.swift */,
				156AA7FE63DC7ED492314667 /* StatsView.swift */,
				4B0CDDA167603EAD06284B99 /* StreamProbeOverlayWindow.swift */,
			);
			path = Overlay;
			sourceTree = "<group>";
		};
```

Replace it entirely with:

```
		F6E07FAD03EAD8D773A2D6E8 /* Overlay */ = {
			isa = PBXFileSystemSynchronizedRootGroup;
			path = Overlay;
			sourceTree = "<group>";
		};
```

(The parent group that references `F6E07FAD03EAD8D773A2D6E8 /* Overlay */` in its `children` stays unchanged — a synchronized root group is still a valid child.)

- [ ] **Step 6: Register the synchronized group on the `iosApp` target**

In the `PBXNativeTarget` block for `iosApp` (GUID `78B53CC79860CBFC2F5543C6`), add a `fileSystemSynchronizedGroups` key right after the `dependencies = ( );` line:

```
			dependencies = (
			);
			fileSystemSynchronizedGroups = (
				F6E07FAD03EAD8D773A2D6E8 /* Overlay */,
			);
			name = iosApp;
```

- [ ] **Step 7: Verify the project still parses**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe/iosApp
plutil -lint iosApp.xcodeproj/project.pbxproj
xcodebuild -project iosApp.xcodeproj -list
```

Expected: `plutil` prints `OK`; `xcodebuild -list` shows the `iosApp` scheme without errors.

- [ ] **Step 8: Baseline build (existing overlay still compiles via the synchronized group)**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **`. If it fails, restore with `cp /tmp/project.pbxproj.bak iosApp.xcodeproj/project.pbxproj` and re-check Steps 2–6.

- [ ] **Step 9: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "build(ios): convert Overlay group to file-system-synchronized group"
```

---

### Task 2: `OverlayTheme.swift` — colors + dot factories

**Files:**
- Create: `iosApp/iosApp/Overlay/OverlayTheme.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// Colors and dot-color factories for the debug overlay.
/// Mirrors `OverlayDrawables.kt`. Hex strings use Android ARGB order (#AARRGGBB)
/// when an alpha is present, otherwise #RRGGBB.
enum OverlayTheme {
    // ── Panel chrome ──────────────────────────────────────────────
    static let panelBg      = UIColor(argbHex: "#E6101024")
    static let headerBg     = UIColor(argbHex: "#331A1A3A")
    static let panelCorner: CGFloat = 14

    // ── Accent + status ───────────────────────────────────────────
    static let accent       = UIColor(argbHex: "#66B2FF")
    static let errorRed     = UIColor(argbHex: "#FF453A")
    static let activeGreen  = UIColor(argbHex: "#30D158")
    static let staleOrange  = UIColor(argbHex: "#FF9F0A")
    static let droppedYellow = UIColor(argbHex: "#FFD60A")
    static let bypassPurple = UIColor(argbHex: "#BF5AF2")
    static let drmCyan      = UIColor(argbHex: "#64D2FF")
    static let inactiveDot  = UIColor(argbHex: "#555555")

    // ── Switch type badges ────────────────────────────────────────
    static let vidBlue      = UIColor(argbHex: "#4FC3F7")
    static let audGreen     = UIColor(argbHex: "#A5D6A7")
    static let subPurple    = UIColor(argbHex: "#CE93D8")

    // ── DRM dots ──────────────────────────────────────────────────
    static let drmAcquiredBlue = UIColor(argbHex: "#0A84FF")
    static let drmReleasedGray = UIColor(argbHex: "#8E8E93")

    // ── Text alphas (white) ───────────────────────────────────────
    static let white100 = UIColor.white
    static let white80  = UIColor.white.withAlphaComponent(0.80)
    static let white60  = UIColor.white.withAlphaComponent(0.60)
    static let white50  = UIColor.white.withAlphaComponent(0.50)
    static let white40  = UIColor.white.withAlphaComponent(0.40)

    // ── Dot factories (mirror OverlayDrawables.kt) ────────────────
    static func cacheDot(_ status: CacheStatus) -> UIColor {
        switch status {
        case .hit:     return activeGreen
        case .miss:    return errorRed
        case .stale:   return staleOrange
        case .bypass:  return bypassPurple
        case .unknown: return inactiveDot
        }
    }

    static func errorCategoryDot(_ category: ErrorCategory) -> UIColor {
        switch category {
        case .loadError:       return errorRed
        case .videoCodecError: return staleOrange
        case .droppedFrames:   return droppedYellow
        case .audioSinkError:  return bypassPurple
        case .audioCodecError: return activeGreen
        case .drmError:        return drmCyan
        }
    }

    static func drmEventDot(_ event: any DrmSessionEvent) -> UIColor {
        switch onEnum(of: event) {
        case .sessionAcquired: return drmAcquiredBlue
        case .keysLoaded:      return activeGreen
        case .sessionReleased: return drmReleasedGray
        case .sessionError:    return drmCyan
        }
    }
}

extension UIColor {
    /// Parses "#RRGGBB" or "#AARRGGBB" (Android ARGB order).
    convenience init(argbHex: String) {
        let hex = argbHex.hasPrefix("#") ? String(argbHex.dropFirst()) : argbHex
        var value: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&value)
        let a, r, g, b: UInt64
        if hex.count == 8 {
            a = (value >> 24) & 0xFF
            r = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            b = value & 0xFF
        } else {
            a = 0xFF
            r = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            b = value & 0xFF
        }
        self.init(red: CGFloat(r) / 255, green: CGFloat(g) / 255,
                  blue: CGFloat(b) / 255, alpha: CGFloat(a) / 255)
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/OverlayTheme.swift
git commit -m "feat(ios-overlay): add OverlayTheme colors and dot factories"
```

---

### Task 3: `OverlayFormattersSwift.swift` — pure formatters

**Files:**
- Create: `iosApp/iosApp/Overlay/OverlayFormattersSwift.swift`

Ported line-by-line from `OverlayFormatters.kt` + `DrmFormatters.kt`.

- [ ] **Step 1: Create the file**

```swift
import Foundation
import StreamProbe

/// Swift port of the internal Kotlin `OverlayFormatters` + `DrmFormatters`
/// (those are `internal` in commonMain and not visible to Swift).
enum OverlayFormattersSwift {

    // ── Numbers ───────────────────────────────────────────────────
    private static func oneDecimal(_ v: Double) -> String {
        String(format: "%.1f", v)
    }

    static func formatBytes(_ value: Int64, suffix: String = "") -> String {
        if value >= 1_000_000 { return "\(oneDecimal(Double(value) / 1_000_000)) MB\(suffix)" }
        if value >= 1_000     { return "\(oneDecimal(Double(value) / 1_000)) KB\(suffix)" }
        return "\(value) B\(suffix)"
    }

    static func formatThroughput(_ bytesPerSec: Int64) -> String {
        formatBytes(bytesPerSec, suffix: "/s")
    }

    static func formatBitrate(_ bps: Int32) -> String {
        if bps >= 1_000_000 { return "\(oneDecimal(Double(bps) / 1_000_000)) Mbps" }
        if bps >= 1_000     { return "\(bps / 1_000) kbps" }
        if bps > 0          { return "\(bps) bps" }
        return "? bps"
    }

    static func formatResolution(_ width: Int32, _ height: Int32) -> String {
        (width > 0 && height > 0) ? "\(width)×\(height)" : "Audio only"
    }

    // ── Segment ───────────────────────────────────────────────────
    static func formatSegmentDetails(_ metric: SegmentMetric) -> String {
        var parts = ["Size: \(formatBytes(metric.sizeBytes))",
                     "TP: \(formatThroughput(metric.throughputBytesPerSec))"]
        if let t = metric.networkTiming {
            parts.append("TTFB: \(formatTtfb(t))")
        }
        return parts.joined(separator: "  ·  ")
    }

    static func formatTtfb(_ timing: NetworkTiming?) -> String {
        guard let t = timing else { return "—" }
        return "\(t.isEstimated ? "~" : "")\(t.ttfbMs)ms"
    }

    // ── Switches ──────────────────────────────────────────────────
    /// "720p → 1080p", or "1.5 Mbps → 5.0 Mbps" when heights match.
    static func formatAbrSwitch(from: ActiveTrackInfo?, to: ActiveTrackInfo) -> String {
        let toLabel = to.height > 0 ? "\(to.height)p" : formatBitrate(to.bitrate)
        guard let from = from else { return "— → \(toLabel)" }
        let fromLabel = from.height > 0 ? "\(from.height)p" : formatBitrate(from.bitrate)
        if fromLabel == toLabel {
            return "\(formatBitrate(from.bitrate)) → \(formatBitrate(to.bitrate))"
        }
        return "\(fromLabel) → \(toLabel)"
    }

    static func formatBufferDuration(_ bufferMs: Int64) -> String {
        "buf: \(oneDecimal(Double(bufferMs) / 1000))s"
    }

    static func formatSwitchReason(_ reason: SwitchReason) -> String {
        switch reason {
        case .initial:   return "INITIAL"
        case .adaptive:  return "ADAPTIVE"
        case .manual:    return "MANUAL"
        case .trickplay: return "TRICKPLAY"
        case .unknown:   return "UNKNOWN"
        }
    }

    static func formatRelativeTimestamp(_ timestampMs: Int64, base baseMs: Int64) -> String {
        let diff = max(timestampMs - baseMs, 0)
        let totalSec = diff / 1000
        let minutes = totalSec / 60
        let seconds = totalSec % 60
        return "+\(minutes):" + String(format: "%02d", seconds)
    }

    // ── Errors ────────────────────────────────────────────────────
    static func formatErrorCategory(_ category: ErrorCategory) -> String {
        switch category {
        case .loadError:       return "LOAD"
        case .videoCodecError: return "CODEC"
        case .droppedFrames:   return "FRAMES"
        case .audioSinkError:  return "AUDIO"
        case .audioCodecError: return "ACODEC"
        case .drmError:        return "DRM"
        }
    }

    private static let absoluteTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    static func formatAbsoluteTimestamp(_ timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestampMs) / 1000)
        return absoluteTimeFormatter.string(from: date)
    }

    static func formatErrorsForExport(_ errors: [PlaybackErrorEvent], baseTimestampMs: Int64) -> String {
        let header = "[StreamProbe] \(errors.count) errors"
        let rows = errors.enumerated().map { (i, e) -> String in
            let rel = formatRelativeTimestamp(e.timestampMs, base: baseTimestampMs)
            let cat = formatErrorCategory(e.category)
            let abs = formatAbsoluteTimestamp(e.timestampMs)
            var line = "#\(i + 1) \(rel) \(cat) \(e.message) [\(abs)]"
            if let d = e.detail, !d.isEmpty { line += "\n    \(d)" }
            return line
        }
        return ([header] + rows).joined(separator: "\n")
    }

    // ── DRM ───────────────────────────────────────────────────────
    static func formatDrmSchemeBadge(_ scheme: DrmScheme) -> String {
        switch scheme {
        case .widevine:  return "WV"
        case .playready: return "PR"
        case .clearkey:  return "CK"
        case .fairplay:  return "FP"
        case .unknown:   return "DRM"
        }
    }

    static func formatDrmSessionState(_ state: DrmSessionState) -> String {
        switch state {
        case .opening:        return "Opening"
        case .opened:         return "Opened"
        case .openedWithKeys: return "Keys Loaded"
        case .released:       return "Released"
        case .error:          return "Error"
        case .unknown:        return "Unknown"
        }
    }

    static func formatDrmEventLabel(_ event: any DrmSessionEvent) -> String {
        switch onEnum(of: event) {
        case .sessionAcquired(let e): return "Session Acquired (\(formatDrmSessionState(e.state)))"
        case .keysLoaded:             return "Keys Loaded"
        case .sessionReleased:        return "Session Released"
        case .sessionError(let e):    return "Error: \(e.message)"
        }
    }

    // ── Rendition helpers ─────────────────────────────────────────
    static func resolveDisplayName(_ languageTag: String) -> String? {
        let name = Locale.current.localizedString(forLanguageCode: languageTag)
        return (name?.isEmpty == false) ? name : nil
    }

    static func channelLabel(_ channelCount: Int32) -> String? {
        switch channelCount {
        case 1: return "mono"
        case 2: return "stereo"
        case 6: return "5.1"
        case 8: return "7.1"
        default: return channelCount > 0 ? "\(channelCount)ch" : nil
        }
    }

    static func subtitleMimeShort(_ mimeType: String?) -> String? {
        switch mimeType {
        case "text/vtt", "application/x-media3-webvtt": return "WebVTT"
        case "application/ttml+xml": return "TTML"
        case "application/x-subrip": return "SRT"
        case "text/x-ssa": return "SSA"
        default: return mimeType.flatMap { $0.components(separatedBy: "/").last }
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/OverlayFormattersSwift.swift
git commit -m "feat(ios-overlay): add OverlayFormattersSwift (port of Kotlin formatters)"
```

---

### Task 4: `HeaderView.swift` — 44pt header bar

**Files:**
- Create: `iosApp/iosApp/Overlay/HeaderView.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// 44pt header bar: title (flex) · error pill · collapse button.
/// Background is headerBg with only the top corners rounded (14pt).
final class HeaderView: UIView {

    let titleLabel = UILabel()
    let errorIndicator = UIButton(type: .system)
    let collapseButton = UIButton(type: .system)

    private let bgLayer = CALayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        bgLayer.backgroundColor = OverlayTheme.headerBg.cgColor
        bgLayer.cornerRadius = OverlayTheme.panelCorner
        bgLayer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        layer.addSublayer(bgLayer)

        // Title — 13pt bold, white, kern 0.52 (0.04em × 13).
        titleLabel.attributedText = NSAttributedString(
            string: "StreamProbe",
            attributes: [
                .font: UIFont.systemFont(ofSize: 13, weight: .bold),
                .foregroundColor: OverlayTheme.white100,
                .kern: 0.52,
            ])
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        // Error pill — red bg, 10pt corner, semibold 11pt white, hidden by default.
        errorIndicator.backgroundColor = OverlayTheme.errorRed
        errorIndicator.layer.cornerRadius = 10
        errorIndicator.titleLabel?.font = .systemFont(ofSize: 11, weight: .bold)
        errorIndicator.setTitleColor(OverlayTheme.white100, for: .normal)
        errorIndicator.tintColor = OverlayTheme.white100
        errorIndicator.contentEdgeInsets = UIEdgeInsets(top: 0, left: 6, bottom: 0, right: 6)
        errorIndicator.isHidden = true
        errorIndicator.translatesAutoresizingMaskIntoConstraints = false

        // Collapse — "▾" 18pt, white60, 32×32 tap target.
        collapseButton.setTitle("▾", for: .normal)
        collapseButton.titleLabel?.font = .systemFont(ofSize: 18)
        collapseButton.setTitleColor(OverlayTheme.white60, for: .normal)
        collapseButton.tintColor = OverlayTheme.white60
        collapseButton.translatesAutoresizingMaskIntoConstraints = false

        addSubview(titleLabel)
        addSubview(errorIndicator)
        addSubview(collapseButton)

        NSLayoutConstraint.activate([
            heightAnchor.constraint(equalToConstant: 44),

            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            titleLabel.centerYAnchor.constraint(equalTo: centerYAnchor),

            errorIndicator.trailingAnchor.constraint(equalTo: collapseButton.leadingAnchor, constant: -4),
            errorIndicator.centerYAnchor.constraint(equalTo: centerYAnchor),
            errorIndicator.heightAnchor.constraint(equalToConstant: 20),
            errorIndicator.widthAnchor.constraint(greaterThanOrEqualToConstant: 36),

            collapseButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            collapseButton.centerYAnchor.constraint(equalTo: centerYAnchor),
            collapseButton.widthAnchor.constraint(equalToConstant: 32),
            collapseButton.heightAnchor.constraint(equalToConstant: 32),
        ])
        titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: errorIndicator.leadingAnchor, constant: -4).isActive = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        bgLayer.frame = bounds
    }

    func applyErrorIndicator(_ indicator: ErrorIndicatorState?) {
        if let ind = indicator {
            errorIndicator.setTitle(ind.text, for: .normal)
            errorIndicator.accessibilityLabel = ind.contentDescription
            errorIndicator.isHidden = false
        } else {
            errorIndicator.isHidden = true
        }
    }

    /// Collapsed → arrow points down (identity); expanded → rotated 180°.
    func applyCollapsed(_ isCollapsed: Bool) {
        collapseButton.transform = isCollapsed ? .identity : CGAffineTransform(rotationAngle: .pi)
        collapseButton.accessibilityLabel = isCollapsed ? "Expand" : "Collapse"
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/HeaderView.swift
git commit -m "feat(ios-overlay): add HeaderView (title, error pill, collapse)"
```

---

### Task 5: `StatsView.swift` — section labels + value rows (rewrite)

**Files:**
- Modify (full rewrite): `iosApp/iosApp/Overlay/StatsView.swift`

- [ ] **Step 1: Replace the file contents**

```swift
import UIKit
import StreamProbe

/// Stats section — vertical stack of (section label → value) pairs, matching Android.
/// DRM label+value are hidden unless `drmVisible`.
final class StatsView: UIView {

    private let stack = UIStackView()

    private let activeTrackLabel = StatsView.valueLabel(size: 14)
    private let audioLabel       = StatsView.valueLabel(size: 12)
    private let subtitleLabel    = StatsView.valueLabel(size: 12)
    private let drmSectionLabel: UILabel
    private let drmValueLabel    = StatsView.valueLabel(size: 12)
    private let segmentLabel     = StatsView.valueLabel(size: 12)
    private let cdnLabel         = StatsView.valueLabel(size: 12)

    private let activeTrackHeader = StatsView.sectionLabel("ACTIVE TRACK")
    private let audioHeader       = StatsView.sectionLabel("AUDIO")
    private let subtitleHeader    = StatsView.sectionLabel("SUBTITLE")
    private let segmentHeader     = StatsView.sectionLabel("LATEST SEGMENT")
    private let cdnHeader         = StatsView.sectionLabel("CDN STATUS")

    override init(frame: CGRect) {
        drmSectionLabel = StatsView.sectionLabel("DRM")
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false

        let rows: [(UILabel, UILabel, CGFloat)] = [
            (activeTrackHeader, activeTrackLabel, 8),
            (audioHeader,       audioLabel,       8),
            (subtitleHeader,    subtitleLabel,    12),
            (drmSectionLabel,   drmValueLabel,    12),
            (segmentHeader,     segmentLabel,     8),
            (cdnHeader,         cdnLabel,         12),
        ]
        for (header, value, gapAfter) in rows {
            stack.addArrangedSubview(header)
            stack.setCustomSpacing(4, after: header)
            stack.addArrangedSubview(value)
            stack.setCustomSpacing(gapAfter, after: value)
        }

        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func render(_ stats: OverlayStatsState) {
        activeTrackLabel.text = stats.activeTrackText
        audioLabel.text       = stats.activeAudioText
        subtitleLabel.text    = stats.activeSubtitleText
        segmentLabel.text     = stats.latestSegmentText
        cdnLabel.text         = stats.cdnStatusText
        drmValueLabel.text    = stats.drmStatusText
        drmSectionLabel.isHidden = !stats.drmVisible
        drmValueLabel.isHidden   = !stats.drmVisible
    }

    // ── Factories ─────────────────────────────────────────────────
    private static func sectionLabel(_ text: String) -> UILabel {
        let l = UILabel()
        l.attributedText = NSAttributedString(
            string: text,
            attributes: [
                .font: UIFont.systemFont(ofSize: 10, weight: .bold),
                .foregroundColor: OverlayTheme.white50,
                .kern: 1.0,
            ])
        l.numberOfLines = 1
        return l
    }

    private static func valueLabel(size: CGFloat) -> UILabel {
        let l = UILabel()
        l.font = .systemFont(ofSize: size, weight: .medium)
        l.textColor = OverlayTheme.white100
        l.numberOfLines = 0
        return l
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/StatsView.swift
git commit -m "feat(ios-overlay): rewrite StatsView with Android section/value hierarchy"
```

---

### Task 6: `ChipBarView.swift` — accent chips (rewrite)

**Files:**
- Modify (full rewrite): `iosApp/iosApp/Overlay/ChipBarView.swift`

- [ ] **Step 1: Replace the file contents**

```swift
import UIKit
import StreamProbe

/// Filter chip row. Checked = accent fill + white text; unchecked = clear fill,
/// 1pt accent border, accent text. Title-case labels. DRM chip hidden unless visible.
final class ChipBarView: UIView {

    var onChipSelected: ((ViewMode) -> Void)?

    private let stack = UIStackView()
    private var chips: [ViewMode: UIButton] = [:]
    private var selectedMode: ViewMode = .tracks

    /// Chips shown in normal mode (ERRORS is reached via the error indicator, not a chip).
    private let modes: [ViewMode] = [.tracks, .segments, .switches, .drm]

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
        ])

        for mode in modes {
            let chip = makeChip(mode)
            chips[mode] = chip
            stack.addArrangedSubview(chip)
        }
        updateStyles()
    }

    required init?(coder: NSCoder) { fatalError() }

    func setSelected(_ mode: ViewMode) {
        selectedMode = mode
        updateStyles()
    }

    func setDrmVisible(_ visible: Bool) {
        chips[.drm]?.isHidden = !visible
    }

    private func makeChip(_ mode: ViewMode) -> UIButton {
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 10, bottom: 4, trailing: 10)
        config.title = title(mode)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 11, weight: .medium)
        btn.layer.cornerRadius = 12
        btn.layer.borderWidth = 1
        btn.clipsToBounds = true
        btn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.selectedMode = mode
            self.updateStyles()
            self.onChipSelected?(mode)
        }, for: .touchUpInside)
        return btn
    }

    private func updateStyles() {
        for (mode, chip) in chips {
            let checked = (mode == selectedMode)
            var config = chip.configuration ?? .plain()
            config.background.backgroundColor = checked ? OverlayTheme.accent : .clear
            config.baseForegroundColor = checked ? OverlayTheme.white100 : OverlayTheme.accent
            chip.configuration = config
            chip.layer.borderColor = (checked ? UIColor.clear : OverlayTheme.accent).cgColor
        }
    }

    private func title(_ mode: ViewMode) -> String {
        switch mode {
        case .tracks:   return "Tracks"
        case .segments: return "Segments"
        case .switches: return "Switches"
        case .drm:      return "DRM"
        case .errors:   return "Errors"
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/ChipBarView.swift
git commit -m "feat(ios-overlay): rewrite ChipBarView with accent style + title-case"
```

---

### Task 7: `ErrorsHeaderView.swift` — errors-mode header

**Files:**
- Create: `iosApp/iosApp/Overlay/ErrorsHeaderView.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit

/// Errors-mode header that replaces the chip row: [← Back] [Errors (N)] [Clear] [↗].
final class ErrorsHeaderView: UIView {

    let backButton  = UIButton(type: .system)
    let titleLabel  = UILabel()
    let clearButton = UIButton(type: .system)
    let shareButton = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        styleTextButton(backButton, title: "← Back", size: 12)
        styleTextButton(clearButton, title: "Clear", size: 12)
        styleTextButton(shareButton, title: "↗", size: 14)
        backButton.accessibilityLabel = "Back to previous view"
        clearButton.accessibilityLabel = "Clear errors"
        shareButton.accessibilityLabel = "Share errors"

        titleLabel.font = .systemFont(ofSize: 12, weight: .bold)
        titleLabel.textColor = OverlayTheme.white100
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        let stack = UIStackView(arrangedSubviews: [backButton, titleLabel, clearButton, shareButton])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        backButton.setContentHuggingPriority(.required, for: .horizontal)
        clearButton.setContentHuggingPriority(.required, for: .horizontal)
        shareButton.setContentHuggingPriority(.required, for: .horizontal)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 32),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func setTitle(_ text: String) { titleLabel.text = text }

    private func styleTextButton(_ btn: UIButton, title: String, size: CGFloat) {
        btn.setTitle(title, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: size)
        btn.setTitleColor(OverlayTheme.accent, for: .normal)
        btn.tintColor = OverlayTheme.accent
        btn.translatesAutoresizingMaskIntoConstraints = false
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/ErrorsHeaderView.swift
git commit -m "feat(ios-overlay): add ErrorsHeaderView (back/title/clear/share)"
```

---

### Task 8: `RenditionCell.swift` — Tracks tab cells

**Files:**
- Create: `iosApp/iosApp/Overlay/RenditionCell.swift`

Two cell classes: a section-header cell and an item cell (video/audio/subtitle).

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// Section header row ("VIDEO" / "AUDIO" / "SUBTITLES") in the Tracks list.
final class RenditionSectionHeaderCell: UITableViewCell {
    static let reuseID = "RenditionSectionHeaderCell"
    private let label = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        label.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            label.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            label.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4),
            label.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(_ title: String) {
        label.attributedText = NSAttributedString(
            string: title,
            attributes: [
                .font: UIFont.systemFont(ofSize: 10, weight: .bold),
                .foregroundColor: OverlayTheme.white50,
                .kern: 1.0,
            ])
    }
}

/// Video / audio / subtitle item: dot + top line + (optional) bottom line.
final class RenditionItemCell: UITableViewCell {
    static let reuseID = "RenditionItemCell"
    private let dot = UIView()
    private let topLine = UILabel()
    private let bottomLine = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        dot.translatesAutoresizingMaskIntoConstraints = false
        dot.layer.cornerRadius = 4
        topLine.font = .systemFont(ofSize: 12, weight: .medium)
        topLine.textColor = OverlayTheme.white100
        topLine.numberOfLines = 0
        topLine.translatesAutoresizingMaskIntoConstraints = false
        bottomLine.font = .systemFont(ofSize: 10)
        bottomLine.textColor = OverlayTheme.white40
        bottomLine.numberOfLines = 0
        bottomLine.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(dot)
        contentView.addSubview(topLine)
        contentView.addSubview(bottomLine)

        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),
            dot.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            dot.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 9),

            topLine.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 8),
            topLine.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            topLine.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),

            bottomLine.leadingAnchor.constraint(equalTo: topLine.leadingAnchor),
            bottomLine.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            bottomLine.topAnchor.constraint(equalTo: topLine.bottomAnchor, constant: 2),
            bottomLine.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(_ row: any OverlayRow) {
        switch onEnum(of: row) {
        case .video(let r):    bindVideo(r.info)
        case .audio(let r):    bindAudio(r.info)
        case .subtitle(let r): bindSubtitle(r.info)
        case .sectionHeader:   break // handled by RenditionSectionHeaderCell
        }
    }

    private func setDot(active: Bool) {
        dot.backgroundColor = active ? OverlayTheme.activeGreen : OverlayTheme.inactiveDot
    }

    private func bindVideo(_ info: VariantInfo) {
        setDot(active: info.isSelected)
        topLine.text = "\(OverlayFormattersSwift.formatResolution(info.width, info.height))  ·  \(OverlayFormattersSwift.formatBitrate(info.bitrate))"
        let codecs = info.codecs ?? ""
        bottomLine.text = codecs
        bottomLine.isHidden = codecs.isEmpty
    }

    private func bindAudio(_ info: AudioTrackInfo) {
        setDot(active: info.isSelected)
        var top: [String] = []
        let name = info.label ?? info.language.flatMap { OverlayFormattersSwift.resolveDisplayName($0) }
        if let name, !name.isEmpty { top.append(name) }
        if let ch = OverlayFormattersSwift.channelLabel(info.channelCount) { top.append(ch) }
        if info.bitrate > 0 { top.append(OverlayFormattersSwift.formatBitrate(info.bitrate)) }
        topLine.text = top.isEmpty ? "Audio" : top.joined(separator: "  ·  ")

        var bottom: [String] = []
        if let c = info.codecs { bottom.append(c) }
        if info.isMuxed { bottom.append("muxed") }
        bottomLine.text = bottom.joined(separator: "  ·  ")
        bottomLine.isHidden = bottom.isEmpty
    }

    private func bindSubtitle(_ info: SubtitleTrackInfo) {
        setDot(active: info.isSelected)
        var top: [String] = []
        let name = info.label ?? info.language.flatMap { OverlayFormattersSwift.resolveDisplayName($0) }
        if let name, !name.isEmpty { top.append(name) }
        if info.kind == .cc { top.append("(CC)") }
        topLine.text = top.isEmpty ? "Subtitle" : top.joined(separator: "  ")

        let mime = OverlayFormattersSwift.subtitleMimeShort(info.mimeType) ?? ""
        bottomLine.text = mime
        bottomLine.isHidden = mime.isEmpty
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/RenditionCell.swift
git commit -m "feat(ios-overlay): add Rendition section-header + item cells"
```

---

### Task 9: `SegmentCell.swift`

**Files:**
- Create: `iosApp/iosApp/Overlay/SegmentCell.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// Segments tab row.
/// Row 1: #N · "DL: Xms" · cache dot.  Row 2 (indented 32pt): size · throughput · TTFB.
final class SegmentCell: UITableViewCell {
    static let reuseID = "SegmentCell"

    private let indexLabel = UILabel()
    private let durationLabel = UILabel()
    private let cacheDot = UIView()
    private let secondaryLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        durationLabel.font = .systemFont(ofSize: 12, weight: .medium)
        durationLabel.textColor = OverlayTheme.white100
        durationLabel.translatesAutoresizingMaskIntoConstraints = false

        cacheDot.layer.cornerRadius = 4
        cacheDot.translatesAutoresizingMaskIntoConstraints = false

        secondaryLabel.font = .systemFont(ofSize: 10)
        secondaryLabel.textColor = OverlayTheme.white60
        secondaryLabel.numberOfLines = 0
        secondaryLabel.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(indexLabel)
        contentView.addSubview(durationLabel)
        contentView.addSubview(cacheDot)
        contentView.addSubview(secondaryLabel)

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            durationLabel.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            durationLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            cacheDot.widthAnchor.constraint(equalToConstant: 8),
            cacheDot.heightAnchor.constraint(equalToConstant: 8),
            cacheDot.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            cacheDot.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            cacheDot.leadingAnchor.constraint(greaterThanOrEqualTo: durationLabel.trailingAnchor, constant: 6),

            secondaryLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            secondaryLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            secondaryLabel.topAnchor.constraint(equalTo: indexLabel.bottomAnchor, constant: 2),
            secondaryLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, metric: SegmentMetric) {
        indexLabel.text = "#\(index + 1)"
        durationLabel.text = "DL: \(metric.totalDurationMs)ms"
        cacheDot.backgroundColor = OverlayTheme.cacheDot(metric.cdnInfo.cacheStatus)
        secondaryLabel.text = OverlayFormattersSwift.formatSegmentDetails(metric)
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/SegmentCell.swift
git commit -m "feat(ios-overlay): add SegmentCell"
```

---

### Task 10: `SwitchCell.swift`

**Files:**
- Create: `iosApp/iosApp/Overlay/SwitchCell.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// Switches tab row.
/// Row 1: #N · type badge (VID/AUD/SUB) · switch text.
/// Row 2 (indent 32pt): buffer · reason · relative timestamp.
final class SwitchCell: UITableViewCell {
    static let reuseID = "SwitchCell"

    private let indexLabel = UILabel()
    private let typeLabel = UILabel()
    private let switchLabel = UILabel()
    private let bufferLabel = UILabel()
    private let reasonLabel = UILabel()
    private let timestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        typeLabel.font = .systemFont(ofSize: 10, weight: .bold)
        typeLabel.translatesAutoresizingMaskIntoConstraints = false

        switchLabel.font = .systemFont(ofSize: 11)
        switchLabel.textColor = OverlayTheme.white100
        switchLabel.numberOfLines = 0
        switchLabel.translatesAutoresizingMaskIntoConstraints = false

        for l in [bufferLabel, reasonLabel] {
            l.font = .systemFont(ofSize: 10)
            l.textColor = OverlayTheme.white60
            l.translatesAutoresizingMaskIntoConstraints = false
        }
        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        [indexLabel, typeLabel, switchLabel, bufferLabel, reasonLabel, timestampLabel]
            .forEach { contentView.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            typeLabel.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            typeLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            typeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 22),

            switchLabel.leadingAnchor.constraint(equalTo: typeLabel.trailingAnchor, constant: 4),
            switchLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            switchLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            bufferLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            bufferLabel.topAnchor.constraint(equalTo: indexLabel.bottomAnchor, constant: 2),
            bufferLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),

            reasonLabel.leadingAnchor.constraint(equalTo: bufferLabel.trailingAnchor, constant: 6),
            reasonLabel.centerYAnchor.constraint(equalTo: bufferLabel.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: reasonLabel.trailingAnchor, constant: 6),
            timestampLabel.centerYAnchor.constraint(equalTo: bufferLabel.centerYAnchor),
            timestampLabel.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -10),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: any TrackSwitchEvent, baseTimestampMs: Int64) {
        indexLabel.text = "#\(index + 1)"
        bufferLabel.text = OverlayFormattersSwift.formatBufferDuration(event.bufferDurationMs)
        reasonLabel.text = OverlayFormattersSwift.formatSwitchReason(event.reason)
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)

        switch onEnum(of: event) {
        case .videoSwitch(let v):
            typeLabel.text = "VID"
            typeLabel.textColor = OverlayTheme.vidBlue
            switchLabel.text = OverlayFormattersSwift.formatAbrSwitch(from: v.previousTrack, to: v.newTrack)
        case .audioSwitch(let a):
            typeLabel.text = "AUD"
            typeLabel.textColor = OverlayTheme.audGreen
            let prev = a.previousTrack.map { $0.label ?? $0.language ?? "?" }
            let next = a.newTrack.label ?? a.newTrack.language ?? "?"
            switchLabel.text = prev != nil ? "\(prev!) → \(next)" : "— → \(next)"
        case .subtitleSwitch(let s):
            typeLabel.text = "SUB"
            typeLabel.textColor = OverlayTheme.subPurple
            let prev = s.previousTrack.map { $0.label ?? $0.language ?? "?" }
            let next = s.newTrack.map { $0.label ?? $0.language ?? "?" } ?? "Off"
            switchLabel.text = prev != nil ? "\(prev!) → \(next)" : "— → \(next)"
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/SwitchCell.swift
git commit -m "feat(ios-overlay): add SwitchCell"
```

---

### Task 11: `ErrorCell.swift` — expandable

**Files:**
- Create: `iosApp/iosApp/Overlay/ErrorCell.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// Errors tab row. Summary line is always visible; the detail block shows when expanded.
/// Expansion state is owned by the data source (the cell only renders it).
final class ErrorCell: UITableViewCell {
    static let reuseID = "ErrorCell"

    private let indexLabel = UILabel()
    private let dot = UIView()
    private let categoryLabel = UILabel()
    private let messageLabel = UILabel()
    private let timestampLabel = UILabel()
    private let chevronLabel = UILabel()

    private let detailStack = UIStackView()
    private let fullMessageLabel = UILabel()
    private let detailLabel = UILabel()
    private let absoluteTimestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        dot.layer.cornerRadius = 4
        dot.translatesAutoresizingMaskIntoConstraints = false

        categoryLabel.font = .systemFont(ofSize: 10, weight: .bold)
        categoryLabel.textColor = OverlayTheme.white80
        categoryLabel.translatesAutoresizingMaskIntoConstraints = false

        messageLabel.font = .systemFont(ofSize: 11)
        messageLabel.textColor = OverlayTheme.white100
        messageLabel.lineBreakMode = .byTruncatingTail
        messageLabel.numberOfLines = 1
        messageLabel.translatesAutoresizingMaskIntoConstraints = false

        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        chevronLabel.font = .systemFont(ofSize: 12)
        chevronLabel.textColor = OverlayTheme.white40
        chevronLabel.textAlignment = .center
        chevronLabel.translatesAutoresizingMaskIntoConstraints = false

        let summary = UIView()
        summary.translatesAutoresizingMaskIntoConstraints = false
        [indexLabel, dot, categoryLabel, messageLabel, timestampLabel, chevronLabel]
            .forEach { summary.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: summary.leadingAnchor, constant: 10),
            indexLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            dot.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            dot.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),

            categoryLabel.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 4),
            categoryLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            messageLabel.leadingAnchor.constraint(equalTo: categoryLabel.trailingAnchor, constant: 4),
            messageLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: messageLabel.trailingAnchor, constant: 4),
            timestampLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            chevronLabel.leadingAnchor.constraint(equalTo: timestampLabel.trailingAnchor, constant: 2),
            chevronLabel.trailingAnchor.constraint(equalTo: summary.trailingAnchor, constant: -10),
            chevronLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            chevronLabel.widthAnchor.constraint(equalToConstant: 20),

            summary.heightAnchor.constraint(greaterThanOrEqualToConstant: 30),
        ])
        messageLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        messageLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        // Detail block
        for l in [fullMessageLabel, detailLabel, absoluteTimestampLabel] {
            l.numberOfLines = 0
            l.translatesAutoresizingMaskIntoConstraints = false
        }
        fullMessageLabel.font = .systemFont(ofSize: 11)
        fullMessageLabel.textColor = OverlayTheme.white80
        detailLabel.font = .systemFont(ofSize: 10)
        detailLabel.textColor = OverlayTheme.white60
        absoluteTimestampLabel.font = .systemFont(ofSize: 10)
        absoluteTimestampLabel.textColor = OverlayTheme.white40

        detailStack.axis = .vertical
        detailStack.spacing = 2
        detailStack.isLayoutMarginsRelativeArrangement = true
        detailStack.layoutMargins = UIEdgeInsets(top: 0, left: 10, bottom: 6, right: 10)
        detailStack.translatesAutoresizingMaskIntoConstraints = false
        [fullMessageLabel, detailLabel, absoluteTimestampLabel].forEach { detailStack.addArrangedSubview($0) }

        let outer = UIStackView(arrangedSubviews: [summary, detailStack])
        outer.axis = .vertical
        outer.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(outer)
        NSLayoutConstraint.activate([
            outer.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            outer.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
            outer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            outer.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            summary.leadingAnchor.constraint(equalTo: outer.leadingAnchor),
            summary.trailingAnchor.constraint(equalTo: outer.trailingAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: PlaybackErrorEvent, baseTimestampMs: Int64, expanded: Bool) {
        indexLabel.text = "#\(index + 1)"
        dot.backgroundColor = OverlayTheme.errorCategoryDot(event.category)
        categoryLabel.text = OverlayFormattersSwift.formatErrorCategory(event.category)
        messageLabel.text = event.message
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)
        chevronLabel.text = expanded ? "▴" : "▾"
        detailStack.isHidden = !expanded
        if expanded {
            fullMessageLabel.text = event.message
            detailLabel.text = event.detail ?? ""
            detailLabel.isHidden = (event.detail ?? "").isEmpty
            absoluteTimestampLabel.text = OverlayFormattersSwift.formatAbsoluteTimestamp(event.timestampMs)
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/ErrorCell.swift
git commit -m "feat(ios-overlay): add expandable ErrorCell"
```

---

### Task 12: `DrmCell.swift`

**Files:**
- Create: `iosApp/iosApp/Overlay/DrmCell.swift`

- [ ] **Step 1: Create the file**

```swift
import UIKit
import StreamProbe

/// DRM tab row: #N · dot · scheme badge · event label · latency (KeysLoaded only) · timestamp.
final class DrmCell: UITableViewCell {
    static let reuseID = "DrmCell"

    private let indexLabel = UILabel()
    private let dot = UIView()
    private let schemeLabel = UILabel()
    private let eventLabel = UILabel()
    private let latencyLabel = UILabel()
    private let timestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        dot.layer.cornerRadius = 4
        dot.translatesAutoresizingMaskIntoConstraints = false

        schemeLabel.font = .systemFont(ofSize: 10, weight: .bold)
        schemeLabel.textColor = OverlayTheme.white80
        schemeLabel.translatesAutoresizingMaskIntoConstraints = false

        eventLabel.font = .systemFont(ofSize: 11)
        eventLabel.textColor = OverlayTheme.white100
        eventLabel.numberOfLines = 0
        eventLabel.translatesAutoresizingMaskIntoConstraints = false

        latencyLabel.font = .systemFont(ofSize: 10)
        latencyLabel.textColor = OverlayTheme.white60
        latencyLabel.translatesAutoresizingMaskIntoConstraints = false

        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        [indexLabel, dot, schemeLabel, eventLabel, latencyLabel, timestampLabel]
            .forEach { contentView.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            dot.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            dot.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),

            schemeLabel.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 4),
            schemeLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            schemeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 24),

            eventLabel.leadingAnchor.constraint(equalTo: schemeLabel.trailingAnchor, constant: 4),
            eventLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            latencyLabel.leadingAnchor.constraint(equalTo: eventLabel.trailingAnchor, constant: 4),
            latencyLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: latencyLabel.trailingAnchor, constant: 6),
            timestampLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            timestampLabel.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -10),
        ])
        eventLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: any DrmSessionEvent, baseTimestampMs: Int64) {
        indexLabel.text = "#\(index + 1)"
        dot.backgroundColor = OverlayTheme.drmEventDot(event)
        schemeLabel.text = OverlayFormattersSwift.formatDrmSchemeBadge(event.scheme)
        eventLabel.text = OverlayFormattersSwift.formatDrmEventLabel(event)
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)

        if case .keysLoaded(let e) = onEnum(of: event) {
            latencyLabel.text = "\(e.licenseLatencyMs)ms"
            latencyLabel.isHidden = false
        } else {
            latencyLabel.isHidden = true
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/DrmCell.swift
git commit -m "feat(ios-overlay): add DrmCell"
```

---

### Task 13: `OverlayTableDataSource.swift` — dispatch + expand + auto-scroll (rewrite)

**Files:**
- Modify (full rewrite): `iosApp/iosApp/Overlay/OverlayTableDataSource.swift`

- [ ] **Step 1: Replace the file contents**

```swift
import UIKit
import StreamProbe

/// Drives the overlay table for all five tabs. Owns error-row expansion state and
/// auto-scroll-to-newest behavior (scroll to last row unless the user scrolled up).
final class OverlayTableDataSource: NSObject, UITableViewDataSource, UITableViewDelegate {

    private var state: OverlayViewState?
    private var expandedRows: Set<Int> = []
    private weak var tableView: UITableView?

    /// True while the last row was visible before the latest update (drives auto-scroll).
    private var wasPinnedToBottom = true

    func register(_ tableView: UITableView) {
        self.tableView = tableView
        tableView.register(RenditionSectionHeaderCell.self, forCellReuseIdentifier: RenditionSectionHeaderCell.reuseID)
        tableView.register(RenditionItemCell.self, forCellReuseIdentifier: RenditionItemCell.reuseID)
        tableView.register(SegmentCell.self, forCellReuseIdentifier: SegmentCell.reuseID)
        tableView.register(SwitchCell.self, forCellReuseIdentifier: SwitchCell.reuseID)
        tableView.register(ErrorCell.self, forCellReuseIdentifier: ErrorCell.reuseID)
        tableView.register(DrmCell.self, forCellReuseIdentifier: DrmCell.reuseID)
        tableView.dataSource = self
        tableView.delegate = self
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 44
        tableView.separatorStyle = .none
    }

    func update(_ newState: OverlayViewState) {
        // Clear expansion when leaving errors mode so stale indices don't persist.
        if state?.mode != newState.mode { expandedRows.removeAll() }
        wasPinnedToBottom = isPinnedToBottom()
        state = newState
        tableView?.reloadData()
        if shouldAutoScroll(for: newState.mode) {
            scrollToBottomIfPinned()
        }
    }

    // MARK: - Counts

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard let s = state else { return 0 }
        switch s.mode {
        case .tracks:   return s.lists.renditionRows.count
        case .segments: return s.lists.segments.count
        case .switches: return s.lists.switches.count
        case .drm:      return s.lists.drmEvents.count
        case .errors:   return s.lists.errors.count
        }
    }

    // MARK: - Cells

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        guard let s = state else { return UITableViewCell() }
        switch s.mode {
        case .tracks:   return tracksCell(tableView, indexPath, s.lists.renditionRows[indexPath.row])
        case .segments: return segmentCell(tableView, indexPath, s.lists.segments[indexPath.row])
        case .switches: return switchCell(tableView, indexPath, s.lists.switches[indexPath.row], s)
        case .drm:      return drmCell(tableView, indexPath, s.lists.drmEvents[indexPath.row], s)
        case .errors:   return errorCell(tableView, indexPath, s.lists.errors[indexPath.row], s)
        }
    }

    private func tracksCell(_ tv: UITableView, _ ip: IndexPath, _ row: any OverlayRow) -> UITableViewCell {
        if case .sectionHeader(let h) = onEnum(of: row) {
            let cell = tv.dequeueReusableCell(withIdentifier: RenditionSectionHeaderCell.reuseID, for: ip) as! RenditionSectionHeaderCell
            cell.bind(h.title)
            return cell
        }
        let cell = tv.dequeueReusableCell(withIdentifier: RenditionItemCell.reuseID, for: ip) as! RenditionItemCell
        cell.bind(row)
        return cell
    }

    private func segmentCell(_ tv: UITableView, _ ip: IndexPath, _ metric: SegmentMetric) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: SegmentCell.reuseID, for: ip) as! SegmentCell
        cell.bind(index: ip.row, metric: metric)
        return cell
    }

    private func switchCell(_ tv: UITableView, _ ip: IndexPath, _ event: any TrackSwitchEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: SwitchCell.reuseID, for: ip) as! SwitchCell
        let base = s.lists.switches.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base)
        return cell
    }

    private func drmCell(_ tv: UITableView, _ ip: IndexPath, _ event: any DrmSessionEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: DrmCell.reuseID, for: ip) as! DrmCell
        let base = s.lists.drmEvents.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base)
        return cell
    }

    private func errorCell(_ tv: UITableView, _ ip: IndexPath, _ event: PlaybackErrorEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: ErrorCell.reuseID, for: ip) as! ErrorCell
        let base = s.lists.errors.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base, expanded: expandedRows.contains(ip.row))
        return cell
    }

    // MARK: - Expand (errors only)

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard state?.mode == .errors else { return }
        if expandedRows.contains(indexPath.row) {
            expandedRows.remove(indexPath.row)
        } else {
            expandedRows.insert(indexPath.row)
        }
        tableView.reloadRows(at: [indexPath], with: .automatic)
    }

    // MARK: - Auto-scroll

    private func shouldAutoScroll(for mode: ViewMode) -> Bool {
        // Timeline tabs append newest at the end; Tracks does not auto-scroll.
        mode == .segments || mode == .switches || mode == .drm || mode == .errors
    }

    private func isPinnedToBottom() -> Bool {
        guard let tv = tableView, tv.contentSize.height > 0 else { return true }
        let bottomEdge = tv.contentOffset.y + tv.bounds.height
        return bottomEdge >= tv.contentSize.height - 24 // ~2 rows of slack
    }

    private func scrollToBottomIfPinned() {
        guard wasPinnedToBottom, let tv = tableView else { return }
        let rows = tableView(tv, numberOfRowsInSection: 0)
        guard rows > 0 else { return }
        DispatchQueue.main.async {
            tv.scrollToRow(at: IndexPath(row: rows - 1, section: 0), at: .bottom, animated: false)
        }
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/OverlayTableDataSource.swift
git commit -m "feat(ios-overlay): rewrite data source (cell dispatch, expand, auto-scroll)"
```

---

### Task 14: `OverlayPanelView.swift` — chrome + portrait/landscape body (rewrite)

**Files:**
- Modify (full rewrite): `iosApp/iosApp/Overlay/OverlayPanelView.swift`

- [ ] **Step 1: Replace the file contents**

```swift
import UIKit
import StreamProbe

/// The draggable overlay panel: rounded navy background, header, and a collapsible body.
/// Body is a vertical stack (portrait) or horizontal split (landscape).
final class OverlayPanelView: UIView {

    let header = HeaderView()
    let statsView = StatsView()
    let chipBar = ChipBarView()
    let errorsHeader = ErrorsHeaderView()
    let tableView = UITableView()

    private let bodyContainer = UIView()
    private var bodyStack: UIStackView!          // axis flips on orientation
    private let leftColumn = UIStackView()        // stats
    private let rightColumn = UIStackView()       // chip/errors header + table
    private var tableHeightConstraint: NSLayoutConstraint!

    private(set) var isLandscape: Bool

    init(isLandscape: Bool) {
        self.isLandscape = isLandscape
        super.init(frame: .zero)
        backgroundColor = OverlayTheme.panelBg
        layer.cornerRadius = OverlayTheme.panelCorner
        layer.masksToBounds = true
        translatesAutoresizingMaskIntoConstraints = false

        configureTable()
        buildColumns()
        buildBody()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func configureTable() {
        tableView.backgroundColor = .clear
        tableView.showsVerticalScrollIndicator = true
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableHeightConstraint = tableView.heightAnchor.constraint(lessThanOrEqualToConstant: 180)
        tableHeightConstraint.priority = .required
        tableHeightConstraint.isActive = true
    }

    private func buildColumns() {
        leftColumn.axis = .vertical
        leftColumn.translatesAutoresizingMaskIntoConstraints = false
        leftColumn.addArrangedSubview(statsView)

        rightColumn.axis = .vertical
        rightColumn.spacing = 6
        rightColumn.translatesAutoresizingMaskIntoConstraints = false
        rightColumn.addArrangedSubview(chipBar)
        rightColumn.addArrangedSubview(errorsHeader)
        rightColumn.addArrangedSubview(tableView)
        errorsHeader.isHidden = true
    }

    private func buildBody() {
        bodyStack = UIStackView()
        bodyStack.axis = isLandscape ? .horizontal : .vertical
        bodyStack.spacing = isLandscape ? 12 : 12
        bodyStack.alignment = .fill
        bodyStack.distribution = isLandscape ? .fillEqually : .fill
        bodyStack.translatesAutoresizingMaskIntoConstraints = false
        bodyStack.addArrangedSubview(leftColumn)
        bodyStack.addArrangedSubview(rightColumn)

        bodyContainer.translatesAutoresizingMaskIntoConstraints = false
        bodyContainer.addSubview(bodyStack)
        NSLayoutConstraint.activate([
            bodyStack.topAnchor.constraint(equalTo: bodyContainer.topAnchor, constant: 10),
            bodyStack.bottomAnchor.constraint(equalTo: bodyContainer.bottomAnchor, constant: -14),
            bodyStack.leadingAnchor.constraint(equalTo: bodyContainer.leadingAnchor, constant: 14),
            bodyStack.trailingAnchor.constraint(equalTo: bodyContainer.trailingAnchor, constant: -14),
        ])

        let root = UIStackView(arrangedSubviews: [header, bodyContainer])
        root.axis = .vertical
        root.translatesAutoresizingMaskIntoConstraints = false
        addSubview(root)
        NSLayoutConstraint.activate([
            root.topAnchor.constraint(equalTo: topAnchor),
            root.bottomAnchor.constraint(equalTo: bottomAnchor),
            root.leadingAnchor.constraint(equalTo: leadingAnchor),
            root.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    /// Caps the list height (180pt portrait; 55% of screen clamped 200–360 landscape).
    func setTableMaxHeight(_ height: CGFloat) {
        tableHeightConstraint.constant = height
    }

    /// Flip the body axis when orientation changes.
    func setLandscape(_ landscape: Bool) {
        isLandscape = landscape
        bodyStack.axis = landscape ? .horizontal : .vertical
        bodyStack.distribution = landscape ? .fillEqually : .fill
        leftColumn.isHidden = false
    }

    func applyCollapsed(_ isCollapsed: Bool) {
        bodyContainer.isHidden = isCollapsed
        header.applyCollapsed(isCollapsed)
    }

    /// Show chip bar in normal mode, errors header in errors mode.
    func applyErrorsMode(_ isErrorsMode: Bool) {
        chipBar.isHidden = isErrorsMode
        errorsHeader.isHidden = !isErrorsMode
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/OverlayPanelView.swift
git commit -m "feat(ios-overlay): rewrite OverlayPanelView (chrome + portrait/landscape body)"
```

---

### Task 15: `OverlayHostViewController.swift` — drag, orientation, render, share (rewrite)

**Files:**
- Modify (full rewrite): `iosApp/iosApp/Overlay/OverlayHostViewController.swift`

- [ ] **Step 1: Replace the file contents**

```swift
import UIKit
import StreamProbe

/// Hosts the overlay panel: positions it, drives the presenter→view render loop,
/// handles drag (from the header only), orientation rebuild, and share.
final class OverlayHostViewController: UIViewController {

    private let presenter: OverlayPresenter
    private let dataSource = OverlayTableDataSource()
    private var panel: OverlayPanelView!
    private var observationTask: Task<Void, Never>?
    private var latestErrors: [PlaybackErrorEvent] = []

    init(presenter: OverlayPresenter) {
        self.presenter = presenter
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        buildPanel()
        startObserving()
        NotificationCenter.default.addObserver(
            self, selector: #selector(orientationChanged),
            name: UIDevice.orientationDidChangeNotification, object: nil)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        observationTask?.cancel()
        observationTask = nil
    }

    // MARK: - Panel construction / positioning

    private func buildPanel() {
        let landscape = view.bounds.width > view.bounds.height
        let p = OverlayPanelView(isLandscape: landscape)
        panel = p
        view.addSubview(p)
        dataSource.register(p.tableView)

        p.header.collapseButton.addTarget(self, action: #selector(collapseToggled), for: .touchUpInside)
        p.header.errorIndicator.addTarget(self, action: #selector(errorIndicatorTapped), for: .touchUpInside)
        p.errorsHeader.backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        p.errorsHeader.clearButton.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        p.errorsHeader.shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        p.chipBar.onChipSelected = { [weak self] mode in self?.presenter.onChipSelected(mode: mode) }

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        p.header.addGestureRecognizer(pan)

        applySizing()
    }

    private func applySizing() {
        let bounds = view.bounds
        let landscape = bounds.width > bounds.height
        panel.setLandscape(landscape)

        let width = min(bounds.width - 32, landscape ? 540 : 310)
        let tableMax: CGFloat = landscape
            ? min(max(bounds.height * 0.55, 200), 360)
            : 180
        panel.setTableMaxHeight(tableMax)

        panel.translatesAutoresizingMaskIntoConstraints = true
        panel.frame.size.width = width
        panel.setNeedsLayout()
        panel.layoutIfNeeded()
        let height = panel.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height)).height
        panel.frame = CGRect(
            x: bounds.width - width - 16,
            y: view.safeAreaInsets.top + 16,
            width: width, height: height)
    }

    @objc private func orientationChanged() {
        // Rebuild for the new orientation; presenter state (mode/collapse) is preserved.
        applySizing()
    }

    // MARK: - Drag (header only), clamped to safe area

    @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
        let t = pan.translation(in: view)
        var newX = panel.frame.origin.x + t.x
        var newY = panel.frame.origin.y + t.y
        let insets = view.safeAreaInsets
        let minX = insets.left
        let maxX = max(view.bounds.width - panel.frame.width - insets.right, minX)
        let minY = insets.top
        let maxY = max(view.bounds.height - panel.frame.height - insets.bottom, minY)
        newX = min(max(newX, minX), maxX)
        newY = min(max(newY, minY), maxY)
        panel.frame.origin = CGPoint(x: newX, y: newY)
        pan.setTranslation(.zero, in: view)
    }

    // MARK: - Intents

    @objc private func collapseToggled() { presenter.onCollapseToggled() }
    @objc private func errorIndicatorTapped() { presenter.onErrorIndicatorTapped() }
    @objc private func backTapped() { presenter.onBackPressed() }
    @objc private func clearTapped() { presenter.onClearErrorsClicked() }

    @objc private func shareTapped() {
        let base = latestErrors.first?.timestampMs ?? 0
        let text = OverlayFormattersSwift.formatErrorsForExport(latestErrors, baseTimestampMs: base)
        let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = panel.errorsHeader.shareButton
        present(vc, animated: true)
    }

    // MARK: - Render loop

    private func startObserving() {
        observationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await state in self.presenter.viewState {
                self.render(state)
            }
        }
    }

    @MainActor
    private func render(_ state: OverlayViewState) {
        latestErrors = state.lists.errors
        panel.header.applyErrorIndicator(state.errorIndicator)
        panel.applyCollapsed(state.isCollapsed)
        panel.applyErrorsMode(state.isErrorsMode)
        panel.chipBar.setSelected(state.mode)
        panel.chipBar.setDrmVisible(state.stats.drmVisible)
        panel.statsView.render(state.stats)
        panel.errorsHeader.setTitle(state.errorsTitle)
        dataSource.update(state)

        // Resize panel height to fit content (width/position unchanged).
        panel.setNeedsLayout()
        panel.layoutIfNeeded()
        let fitted = panel.systemLayoutSizeFitting(
            CGSize(width: panel.frame.width, height: UIView.layoutFittingCompressedSize.height)).height
        panel.frame.size.height = fitted
    }
}
```

- [ ] **Step 2: Build**

Run the canonical build command.
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Commit**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe
git add iosApp/iosApp/Overlay/OverlayHostViewController.swift
git commit -m "feat(ios-overlay): rewrite host VC (drag, orientation, render, share)"
```

---

### Task 16: Visual verification in the simulator

**Files:** none (verification only)

- [ ] **Step 1: Boot the simulator and install the app**

```bash
cd /Users/oguzhaneksi/StudioProjects/StreamProbe/iosApp
xcrun simctl boot "iPhone 14 Pro" 2>/dev/null || true
open -a Simulator
xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 14 Pro' \
  -configuration Debug -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "iPhone 14 Pro" "build/Build/Products/Debug-iphonesimulator/iosApp.app"
xcrun simctl launch "iPhone 14 Pro" $(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" build/Build/Products/Debug-iphonesimulator/iosApp.app/Info.plist)
```

- [ ] **Step 2: Let the stream play ~20s, then screenshot the overlay**

```bash
sleep 20
xcrun simctl io "iPhone 14 Pro" screenshot /tmp/overlay-tracks.png
```

Then tap each chip (Segments, Switches, DRM) and the error indicator in the running simulator, screenshotting each:

```bash
xcrun simctl io "iPhone 14 Pro" screenshot /tmp/overlay-segments.png
xcrun simctl io "iPhone 14 Pro" screenshot /tmp/overlay-switches.png
xcrun simctl io "iPhone 14 Pro" screenshot /tmp/overlay-errors.png
```

- [ ] **Step 3: Compare against the Android reference and the spec**

Open each screenshot and verify against `docs/superpowers/specs/2026-06-16-ios-overlay-design.md`:
- Panel: dark navy `#101024`-family bg, 14pt corners, header bar with lighter bg.
- Stats: section labels (`ACTIVE TRACK`, `AUDIO`…) above values; correct font hierarchy.
- Chips: `#66B2FF` accent fill when selected, outline when not; title-case labels.
- Tracks rows: colored dots (green = active), top line + codec subline.
- Segments/Switches/DRM rows: index, dots/badges, two-line layout where applicable.
- Errors: red indicator pill; errors-mode header (← Back · Errors (N) · Clear · ↗); tapping a row expands it.

- [ ] **Step 4: Rotate to landscape and verify the split layout**

```bash
xcrun simctl io "iPhone 14 Pro" screenshot /tmp/overlay-landscape.png
```

Verify: panel widens (~540pt), stats on the left column, chips + list on the right column.

- [ ] **Step 5: Record the result**

If any screen diverges from the spec, file the specific gap and return to the relevant task. If all screens match, the implementation is complete. (No commit — verification only.)

---

## Notes for the implementer

- **Drag uses manual frames, not AutoLayout.** The panel is positioned with `frame` in `OverlayHostViewController`; internal panel layout uses AutoLayout. This matches the existing host VC approach and Android's `addContentView` + `x/y` drag.
- **Presenter is shared and long-lived** — never recreate it on rotation; mode and collapse state must survive (mirrors Android reusing `OverlayPresenter` across rebuilds).
- **`StreamProbeOverlayWindow` and `SceneDelegate` wiring are unchanged** — the host VC swap is internal.
- **If a SKIE name fails to compile**, check the generated interface at `sdk/build/XCFrameworks/debug/StreamProbe.xcframework/ios-arm64_x86_64-simulator/StreamProbe.framework/Modules/StreamProbe.swiftmodule/*.swiftinterface` for the exact spelling.
