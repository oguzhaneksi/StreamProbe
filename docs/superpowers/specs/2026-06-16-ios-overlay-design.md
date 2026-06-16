# iOS Overlay — Android Parity Design Spec

**Date:** 2026-06-16  
**Scope:** Redesign the existing `iosApp/iosApp/Overlay/` UIKit overlay to be pixel-for-pixel identical to the Android `androidMain` overlay.  
**Location:** Stays in `iosApp` (host-app side). No SDK changes required.  
**Approach:** Full Android Parity (Option A) — custom UIView cells per tab, landscape split layout, drag-from-header, expandable error rows.

---

## 1. Files Affected

All files are under `iosApp/iosApp/Overlay/`. Every file is a complete rewrite.

| File | Role |
|------|------|
| `OverlayPanelView.swift` | Root panel view — panel chrome, header, body layout (portrait/landscape) |
| `StatsView.swift` | Stats section — section labels + value rows |
| `ChipBarView.swift` | Filter chip row (normal mode) |
| `ErrorsHeaderView.swift` | ← Back · Errors (N) · Clear · ↗ (errors mode, new file) |
| `OverlayDrawables.swift` | Color/dot factory functions (mirrors `OverlayDrawables.kt`, new file) |
| `OverlayFormattersSwift.swift` | Swift-side formatting helpers for list cells (see §13) |
| `OverlayHostViewController.swift` | Coordinator — wires presenter → render, orientation, drag clamp |
| `OverlayTableDataSource.swift` | UITableView data source + delegate (cell dispatch, expand state) |
| `RenditionCell.swift` | Tracks tab cell (new file) |
| `SegmentCell.swift` | Segments tab cell (new file) |
| `SwitchCell.swift` | Switches tab cell (new file) |
| `ErrorCell.swift` | Errors tab cell — expandable (new file) |
| `DrmCell.swift` | DRM tab cell (new file) |
| `StreamProbeOverlayWindow.swift` | Unchanged — hit-test pass-through |

---

## 2. Color Tokens

All colors match `OverlayDrawables.kt` exactly.

| Token | Hex | Usage |
|-------|-----|-------|
| `panelBg` | `#E6101024` | Panel background (dark navy, ~90% opaque) |
| `headerBg` | `#331A1A3A` | Header background (top corners only) |
| `accent` | `#66B2FF` | Chip fill (checked), chip border/text (unchecked), Back/Clear/Share buttons |
| `errorRed` | `#FF453A` | Error indicator pill, LOAD/error dot |
| `activeGreen` | `#30D158` | Active variant dot, cache HIT dot, DRM KeysLoaded dot |
| `staleOrange` | `#FF9F0A` | Cache STALE dot, VIDEO_CODEC_ERROR dot |
| `droppedYellow` | `#FFD60A` | DROPPED_FRAMES dot |
| `bypassPurple` | `#BF5AF2` | Cache BYPASS dot, AUDIO_SINK_ERROR dot |
| `drmCyan` | `#64D2FF` | DRM_ERROR dot, DRM SessionError dot |
| `vidBlue` | `#4FC3F7` | VID type badge in Switches tab |
| `audGreen` | `#A5D6A7` | AUD type badge in Switches tab |
| `subPurple` | `#CE93D8` | SUB type badge in Switches tab |
| `drmAcquiredBlue` | `#0A84FF` | DRM SessionAcquired dot |
| `drmReleasedGray` | `#8E8E93` | DRM SessionReleased dot |
| `inactiveDot` | `#555555` | Inactive variant dot, cache UNKNOWN dot |
| `white100` | `#FFFFFFFF` | Primary text, checked chip text |
| `white80` | `#CCFFFFFF` | Error category badge, DRM scheme badge, error detail full message |
| `white60` | `#99FFFFFF` | Collapse button, row index labels, secondary text, DRM latency |
| `white50` | `#80FFFFFF` | Section labels, DRM section label |
| `white40` | `#66FFFFFF` | Timestamps, rendition bottom line, chevron |

---

## 3. Typography

| Element | Size | Weight | Notes |
|---------|------|--------|-------|
| Panel title "StreamProbe" | 13pt | Bold + medium | Letter spacing 0.04em |
| Section labels (ACTIVE TRACK…) | 10pt | Bold | All-caps, letter spacing 0.1em, `white50` |
| Active track value | 14pt | Medium (`.medium`) | `white100` |
| Audio / subtitle / segment / CDN / DRM values | 12pt | Medium | `white100` |
| Chips | 11pt | Medium | Title-case labels |
| Error indicator | 11pt | Semibold | `white100` on `errorRed` pill |
| Collapse button `▾` | 18pt | Regular | `white60` |
| Back / Clear / errors title / share | 12pt / 12pt / 12pt / 14pt | Regular / Regular / Bold / Regular | `accent` / `accent` / `white100` / `accent` |
| Row index `#N` | 11pt | Medium | `white60`, min-width 28pt |
| Rendition top line | 12pt | Medium | `white100` |
| Rendition bottom line | 10pt | Regular | `white40` |
| Segment duration | 12pt | Medium | `white100` |
| Segment secondary | 10pt | Regular | `white60` |
| Switch type badge (VID/AUD/SUB) | 10pt | Bold | Colored (`vidBlue`/`audGreen`/`subPurple`) |
| Switch main text | 11pt | Regular | `white100` |
| Switch secondary (buf · reason) | 10pt | Regular | `white60` |
| Switch/DRM timestamp | 10pt | Regular | `white40` |
| Error category badge | 10pt | Bold | `white80` |
| Error message (summary) | 11pt | Regular | `white100`, 1 line, truncated |
| Error chevron `▾`/`▴` | 12pt | Regular | `white40` |
| Error full message (detail) | 11pt | Regular | `white80` |
| Error structured detail | 10pt | Regular | `white60` |
| Error absolute timestamp | 10pt | Regular | `white40` |
| DRM scheme badge (WV/PR…) | 10pt | Bold | `white80`, min-width 24pt |
| DRM event label | 11pt | Regular | `white100` |
| DRM latency | 10pt | Regular | `white60` |
| Section header (VIDEO/AUDIO/SUBTITLES) | 10pt | Bold | `white50`, letter spacing 0.1em |

---

## 4. Layout — Portrait

Panel orientation: **vertical UIStackView** (axis `.vertical`).

```
OverlayPanelView (310pt wide, WRAP_CONTENT tall)
├── HeaderView (44pt tall)
│   ├── Title label (flex 1)
│   ├── ErrorIndicatorButton (hidden when no errors, cornerRadius 10pt)
│   └── CollapseButton (32×32pt tap target)
└── BodyView (padding: 14pt H, 10pt top, 14pt bottom) — hidden when collapsed
    ├── sectionLabel("ACTIVE TRACK") mb:4pt
    ├── activeTrackLabel           mb:8pt
    ├── sectionLabel("AUDIO")      mb:4pt
    ├── audioLabel                 mb:8pt
    ├── sectionLabel("SUBTITLE")   mb:4pt
    ├── subtitleLabel              mb:12pt
    ├── drmSectionLabel            mb:4pt  ← hidden when !drmVisible
    ├── drmStatusLabel             mb:12pt ← hidden when !drmVisible
    ├── sectionLabel("LATEST SEGMENT") mb:4pt
    ├── segmentLabel               mb:8pt
    ├── sectionLabel("CDN STATUS") mb:4pt
    ├── cdnLabel                   mb:12pt
    ├── ChipBarView                mb:6pt  ← hidden in ERRORS mode
    ├── ErrorsHeaderView           mb:6pt  ← hidden in normal mode
    └── UITableView (max-height capped — see §6)
```

---

## 5. Layout — Landscape

Panel orientation: **horizontal UIStackView** (axis `.horizontal`), 540pt wide.

```
OverlayPanelView (540pt wide)
├── HeaderView (44pt tall, full width)
└── BodyView (padding: 14pt H, 10pt top, 14pt bottom)
    ├── LeftColumn (flex 1, marginEnd 12pt) — stat sections only
    │   ├── sectionLabel("ACTIVE TRACK") mb:4pt
    │   ├── activeTrackLabel             mb:8pt
    │   ├── ... (same order as portrait, no chips/list)
    │   └── cdnLabel
    └── RightColumn (flex 1) — chips + list
        ├── ChipBarView / ErrorsHeaderView (mb:6pt)
        └── UITableView (max-height capped — see §6)
```

**Landscape list max height:** `(screenHeight * 0.55).clamped(to: 200...360)` — same formula as Android.

---

## 6. UITableView Sizing

The `UITableView` is bounded so it doesn't grow the overlay beyond a sensible height:

- **Portrait:** fixed max-height `180pt` (same as Android's `180dp`).
- **Landscape:** `(UIScreen.main.bounds.height * 0.55).clamped(to: 200...360)` pt.
- Implemented via `tableView.heightAnchor.constraint(lessThanOrEqualToConstant: maxHeight)` with `.required` priority.
- `isScrollEnabled = true`, vertical scroll bar enabled, fading edge (same as Android's `setFadingEdgeLength(12dp)`) via a gradient mask layer of 12pt.

---

## 7. Panel Sizing & Positioning

| Orientation | Width |
|------------|-------|
| Portrait | `min(UIScreen.main.bounds.width − 32, 310)` pt |
| Landscape | `min(UIScreen.main.bounds.width − 32, 540)` pt |

Initial position: top-right, offset `(x: screenWidth − panelWidth − 16, y: safeAreaInsets.top + 16)`.  
On orientation change: panel re-positions to top-right of the new screen bounds.

---

## 8. Header (`HeaderView`)

- Height: **44pt** constraint.
- Background: `#331A1A3A` — top corners rounded 14pt only. Implemented as a `CALayer` sublayer with `maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]` and `cornerRadius = 14`.
- Padding: 14pt leading, 6pt trailing.
- **Title label:** "StreamProbe", 13pt bold medium, white, letter-spacing via `NSAttributedString` kern 0.04em.
- **Error indicator:** `UIButton`, background `#FF453A`, `cornerRadius = 10`, h-pad 6pt, min-width 48pt, min-height 24pt (touch target stays 44pt via `UIEdgeInsets`), marginTrailing 4pt. Hidden (`isHidden = true`) when `errorIndicator == nil`.
- **Collapse button:** "▾" character, 18pt, `white60`, 32×32pt frame. Tap → `presenter.onCollapseToggled()`. Expanded state: `transform = CGAffineTransform(rotationAngle: .pi)`; collapsed: `.identity`.

---

## 9. Stats Section (`StatsView`)

Replaced from the current emoji-prefix monospaced version with a proper label-hierarchy layout:

```swift
// For each stat field:
let sectionLabel = UILabel()  // 10pt bold, white50, ALL-CAPS, letter-spacing 0.1em
let valueLabel   = UILabel()  // 12pt medium (14pt for activeTrack), white100
```

Vertical `UIStackView` with `spacing = 0`; margins applied as `UIEdgeInsets` on each label via `stackView.setCustomSpacing(N, after: label)`.

Spacing matches Android exactly:
- After each section label: **4pt**
- After each value label: **8pt** (12pt before chip row, and after subtitle/DRM blocks)

---

## 10. Chip Bar (`ChipBarView`)

Replaces the current white/black chip style with the Android accent style:

- **Checked chip:** fill `#66B2FF`, text `white100`, `cornerRadius = 12`, no border.
- **Unchecked chip:** fill clear, border 1pt `#66B2FF`, text `#66B2FF`, `cornerRadius = 12`.
- Padding: 10pt horizontal, 4pt vertical.
- Font: 11pt medium.
- Labels: **Title-case** — "Tracks", "Segments", "Switches", "DRM" (not ALL CAPS).
- DRM chip hidden (`isHidden`) when `!stats.drmVisible`.
- Horizontal `UIStackView`, spacing 6pt, embedded in a `UIScrollView` for narrow screens.

---

## 11. Errors Header View (`ErrorsHeaderView`) — new file

Shown in place of `ChipBarView` when `state.isErrorsMode == true`.

```
[← Back]   [     Errors (N)     ]   [Clear]  [↗]
```

- **← Back** (UIButton): 12pt, `accent`, min tap size 48×32pt. Action → `presenter.onBackPressed()`.
- **Errors title** (UILabel): 12pt bold, `white100`, centered, flex.
- **Clear** (UIButton): 12pt, `accent`, min tap size 48×32pt. Action → `presenter.onClearErrorsClicked()`.
- **↗ Share** (UIButton): 14pt, `accent`, min tap size 48×32pt. Action → share via `UIActivityViewController`.

Share text: `OverlayFormattersSwift.formatErrorsForExport(errors:baseTimestampMs:)` — mirrors `OverlayFormatters.formatErrorsForExport` logic.

---

## 12. List Cells

### 12.1 Rendition Cell (`RenditionCell`) — Tracks tab

Handles `OverlayRow.SectionHeader`, `.Video`, `.Audio`, `.Subtitle`.

**SectionHeader:** Full-width label, 10pt bold, `white50`, letter-spacing 0.1em, h-pad 10pt, v-pad 4pt.

**Video / Audio / Subtitle item:**
```
[dot 8×8]  [topLine 12pt medium white100]
            [bottomLine 10pt white40]  (marginStart 16pt = dot 8 + gap 8)
```
- Dot: `CALayer` oval. Green (`activeGreen`) if `isSelected`, gray (`inactiveDot`) otherwise.
- Top line content: same as `RenditionItemView.kt` — `"1920×1080  ·  5.0 Mbps"`, audio channel/bitrate, subtitle label/CC.
- Bottom line: codecs, `"muxed"` tag, MIME short name.
- Cell padding: 10pt h, 6pt v.

### 12.2 Segment Cell (`SegmentCell`) — Segments tab

```
Row 1: [#N 11pt white60 min28pt]  [DL: Xms 12pt white100 flex]  [cacheDot 8×8]
Row 2: [secondary 10pt white60, marginStart 32pt, topMargin 2pt]
```
Cache dot colors: HIT=`activeGreen`, MISS=`errorRed`, STALE=`staleOrange`, BYPASS=`bypassPurple`, UNKNOWN=`inactiveDot`.  
Secondary text: `OverlayFormattersSwift.formatSegmentDetails(metric)`.

### 12.3 Switch Cell (`SwitchCell`) — Switches tab

```
Row 1: [#N white60 min28]  [VID/AUD/SUB 10pt bold colored min22]  [switch text 11pt white100 flex]
Row 2: [buf: Xs white60, indent 32pt]  [REASON white60, +6pt]  [+M:SS white40, +6pt]
```
Type badge colors: VID=`vidBlue`, AUD=`audGreen`, SUB=`subPurple`.  
Switch text: `OverlayFormattersSwift.formatAbrSwitch(from:to:)` for video; language label for audio/subtitle.

### 12.4 Error Cell (`ErrorCell`) — Errors tab — expandable

**Summary row** (always visible):
```
[#N white60 min28]  [dot 8×8 +4pt]  [CATEGORY 10pt bold white80 +4pt]
[message 11pt white100 flex 1-line truncated +4pt]  [+M:SS white40 +4pt]  [▾/▴ 12pt white40 20pt +2pt]
```

**Detail container** (visible when expanded, `isHidden` toggle):
```
padding: 10pt h, 0 top, 6pt bottom
├── fullMessage  11pt white80  bottomMargin 2pt
├── detail       10pt white60  bottomMargin 2pt
└── absTimestamp 10pt white40
```

Expand/collapse state tracked in `OverlayTableDataSource.expandedIndexPaths: Set<IndexPath>`.  
Tap → toggle membership, call `tableView.reloadRows(at:with:)`.  
Dot color per `ErrorCategory`: LOAD=`errorRed`, VIDEO_CODEC=`staleOrange`, DROPPED=`droppedYellow`, AUDIO_SINK=`bypassPurple`, AUDIO_CODEC=`activeGreen`, DRM=`drmCyan`.

### 12.5 DRM Cell (`DrmCell`) — DRM tab

Single row:
```
[#N white60 min28]  [dot 8×8 +4pt]  [WV/PR/CK/FP 10pt bold white80 min24 +4pt]
[event label 11pt white100 flex +4pt]  [Xms white60 +4pt, hidden unless KeysLoaded]
[+M:SS white40 +6pt]
```
Dot colors: SessionAcquired=`drmAcquiredBlue`, KeysLoaded=`activeGreen`, SessionReleased=`drmReleasedGray`, SessionError=`drmCyan`.  
Scheme badge: `OverlayFormattersSwift.formatDrmSchemeBadge(scheme)` — "WV", "PR", "CK", "FP", "DRM".  
Event label: `OverlayFormattersSwift.formatDrmEventLabel(event)`.

---

## 13. `OverlayFormattersSwift.swift` (new file)

`OverlayFormatters` and `DrmFormatters` are `internal` Kotlin objects — they are not visible to the Swift host app. List cells therefore need Swift-side equivalents. This file replicates only the functions needed by the five cell types:

```swift
// Segment cell
static func formatSegmentDetails(_ metric: SegmentMetric) -> String
// e.g. "Size: 512.0 KB  ·  TP: 12.1 MB/s  ·  TTFB: ~45ms"

// Switch cell
static func formatAbrSwitch(from: ActiveTrackInfo?, to: ActiveTrackInfo) -> String
static func formatBufferDuration(_ bufferMs: Int64) -> String   // "buf: 8.2s"
static func formatSwitchReason(_ reason: SwitchReason) -> String // "ADAPTIVE"
static func formatRelativeTimestamp(_ ts: Int64, base: Int64) -> String // "+0:42"

// Error cell
static func formatErrorCategory(_ category: ErrorCategory) -> String // "LOAD"
static func formatAbsoluteTimestamp(_ ts: Int64) -> String  // "HH:mm:ss.SSS"
static func formatErrorsForExport(_ errors: [PlaybackErrorEvent], baseTimestampMs: Int64) -> String

// DRM cell
static func formatDrmSchemeBadge(_ scheme: DrmScheme) -> String  // "WV", "PR"…
static func formatDrmEventLabel(_ event: any DrmSessionEvent) -> String

// Rendition cell
static func resolveDisplayName(languageTag: String) -> String?
// Swift: Locale(identifier: tag).localizedString(forLanguageCode: tag)
```

All format logic mirrors `OverlayFormatters.kt` and `DrmFormatters.kt` exactly. Tests (if any) compare output against the common Kotlin formatters.

---

## 14. `OverlayDrawables.swift` (new file)

Mirrors `OverlayDrawables.kt`. Static factory functions returning `UIColor` and `CAShapeLayer`:

```swift
static func cacheDot(status: CacheStatus) -> UIColor
static func errorCategoryDot(category: ErrorCategory) -> UIColor
static func drmEventDot(event: any DrmSessionEvent) -> UIColor
```

Hex color extension: `UIColor(hex: "#FF453A")` added as a private extension.

---

## 14. Behaviors

### Drag
- Move `UIPanGestureRecognizer` from `OverlayPanelView` to `headerView` only.
- `panGesture.translation` updates `panel.center`; after update, clamp:

```swift
let safeArea = view.safeAreaInsets
panel.frame.origin.x = panel.frame.origin.x.clamped(to: safeArea.left ... (bounds.width - panel.frame.width - safeArea.right))
panel.frame.origin.y = panel.frame.origin.y.clamped(to: safeArea.top ... (bounds.height - panel.frame.height - safeArea.bottom))
```

### Collapse
- `panel.bodyView.isHidden = state.isCollapsed`
- `collapseButton.transform = state.isCollapsed ? .identity : CGAffineTransform(rotationAngle: .pi)`

### Orientation rebuild
- Subscribe to `UIDevice.orientationDidChangeNotification` in `OverlayHostViewController.viewDidLoad`.
- On change: update `bodyStack.axis` (`.vertical` portrait / `.horizontal` landscape), update `tableView` max-height constraint constant, update panel width constraint, snap panel to top-right.
- Presenter is **not** recreated — ViewMode and collapse state survive rotation (same as Android).

### Auto-scroll
- In `OverlayTableDataSource.update(_:tableView:)`, after `reloadData`, check if the list was showing the last row before the update. If yes, scroll to `IndexPath(row: lastIndex, section: 0)`.
- Track last visible index in `didEndDisplaying` delegate.

### Errors mode swap
- `chipBarView.isHidden = state.isErrorsMode`
- `errorsHeaderView.isHidden = !state.isErrorsMode`

### DRM visibility
- `drmSectionLabel.isHidden = !state.stats.drmVisible`
- `drmStatusLabel.isHidden = !state.stats.drmVisible`
- `drmChip.isHidden = !state.stats.drmVisible`

### Share
```swift
let text = OverlayFormattersSwift.formatErrorsForExport(errors: errors, baseTimestampMs: firstTimestamp)
let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
present(vc, animated: true)
```

---

## 15. `OverlayTableDataSource` Changes

Full rewrite from the current `textLabel`-only implementation:

- Register 6 cell types: `RenditionSectionHeaderCell`, `RenditionItemCell`, `SegmentCell`, `SwitchCell`, `ErrorCell`, `DrmCell`.
- `numberOfSections` = 1 (all tabs use a flat section).
- `cellForRowAt` dispatches on `state.mode` and row model type.
- `estimatedRowHeight = 44`, `rowHeight = UITableView.automaticDimension` (self-sizing cells).
- `expandedIndexPaths: Set<IndexPath>` — owned here, cleared when mode changes.
- `tableView(_:didSelectRowAt:)` — toggle expand for error rows only.

---

## 16. Files Not Changed

- `StreamProbeOverlayWindow.swift` — hit-test pass-through unchanged.
- `SceneDelegate.swift` — wiring unchanged.
- `PlayerViewController.swift` — unchanged.
- All `sdk/src/` files — no SDK changes.

---

## 17. Out of Scope

- Moving overlay into the SDK (`iosMain`) — explicitly deferred.
- SwiftUI implementation — UIKit only.
- Accessibility (VoiceOver) — not in scope for this pass.
- Dark/light mode adaptation — overlay is always dark (same as Android).
