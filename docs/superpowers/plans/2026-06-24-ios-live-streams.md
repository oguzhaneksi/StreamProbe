# iOS HLS Live Streams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HLS live streams to the iOS demo app and adapt the player UI (DVR scrubber, "● LIVE" badge, hidden seek ±10 s) so live content is presented correctly.

**Architecture:** A new `isLive` flag on the `Stream` model is threaded through `PlayerViewModel.attach(…isLive:)` — no async detection, so no UI flash. `PlayerEngine` gains a `seekableRangePublisher` exposing the DVR window from `AVPlayerItem.seekableTimeRanges`; the view-model derives DVR-relative scrubber coordinates and live time labels from it. `PlayerControlsView` and `StreamRow` branch inline on `isLive`.

**Tech Stack:** Swift 5, SwiftUI, AVFoundation, Combine, XCTest. XcodeGen project (`iosApp`).

## Global Constraints

- **iOS demo app only.** No changes to `sdk/`, the Android app, or KMP common code.
- **No new files.** Every change modifies an existing file, so the XcodeGen project does **not** need regenerating (`iosApp/generate.sh` is unnecessary). All edited Swift files already belong to a directory-based source set.
- **HLS-only, no DRM.** Live entries are public, unencrypted `.m3u8` HTTPS URLs.
- **ASCII hyphen in time labels.** The existing VOD `remainingText` returns `"-MM:SS"` (ASCII `-`, U+002D). Live labels reuse the same ASCII hyphen for parity — do **not** introduce the Unicode minus the spec rendered.
- **The "● LIVE" badge is informational only** (not tappable).
- **Deployment target iOS 15**, Swift version 5.0.

## Setup (one-time, before Task 1)

The `iosApp` Xcode project links the local `StreamProbe` Swift package, which wraps the debug XCFramework. If the framework is not yet built in this worktree, build it once:

```bash
./gradlew :sdk:assembleStreamProbeCoreDebugXCFramework
```

Expected: `BUILD SUCCESSFUL`. (Skip if already built — nothing in this plan changes SDK code.)

**Test command used throughout this plan** (single class shown; adjust `-only-testing:`):

```bash
xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' \
  -only-testing:iosAppTests/StreamCatalogTests \
  2>&1 | tail -25
```

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `iosApp/iosApp/Streams/Stream.swift` | Stream model + catalog | Add `isLive` flag; append live entries |
| `iosApp/iosApp/Player/PlayerEngine.swift` | Engine protocol + `AVPlayerEngine` | Add `seekableRangePublisher` + DVR-window tracking |
| `iosApp/iosApp/Player/PlayerViewModel.swift` | Player state/derivation | Add `isLive`, `seekableRange`, DVR-relative scrub + live labels |
| `iosApp/iosApp/Player/PlayerControlsView.swift` | Player controls UI | Branch on `isLive`: LIVE badge, hide seek±10, DVR scrubber |
| `iosApp/iosApp/Player/PlayerScreen.swift` | Player lifecycle | Pass `stream.isLive` into `attach` |
| `iosApp/iosApp/Streams/StreamSelectionScreen.swift` | Stream list | LIVE vs HLS badge in `StreamRow` |
| `iosApp/iosAppTests/MockPlayerEngine.swift` | Test double | Add `seekableRangeSubject` + publisher |
| `iosApp/iosAppTests/StreamCatalogTests.swift` | Catalog tests | Live/VOD/unique-id tests |
| `iosApp/iosAppTests/PlayerViewModelTests.swift` | View-model tests | Live seek/label/scrub tests |

---

## Task 1: Stream model — `isLive` flag + live catalog entries

**Files:**
- Modify: `iosApp/iosApp/Streams/Stream.swift`
- Test: `iosApp/iosAppTests/StreamCatalogTests.swift`

**Interfaces:**
- Produces: `Stream.isLive: Bool` (stored, default `false`); `init(title:urlString:isLive:)` with `isLive` defaulting to `false`. `demoStreams: [Stream]` keeps its 5 VOD entries and gains ≥1 `isLive: true` entry.

- [ ] **Step 1: Validate the two candidate live URLs are reachable**

```bash
for u in \
  "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8" \
  "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8"; do
  echo "== $u"; curl -sSL -o /dev/null -w "%{http_code}\n" --max-time 15 "$u"
done
```

Expected: each prints `200`. If one is not `200`, drop it and keep the working one (the catalog needs only ≥1 live entry). Do not add an unreachable URL.

- [ ] **Step 2: Write the failing catalog tests**

Append to `iosApp/iosAppTests/StreamCatalogTests.swift` (inside the class):

```swift
    func test_atLeastOneLiveStream_exists() {
        XCTAssertTrue(demoStreams.contains { $0.isLive }, "catalog must contain a live stream")
    }

    func test_vodStreams_areNotLive() {
        let vod = demoStreams.filter { !$0.isLive }
        XCTAssertEqual(vod.count, 5, "the 5 original VOD entries must remain non-live")
    }

    func test_allStreams_haveUniqueIds() {
        let ids = demoStreams.map(\.id)
        XCTAssertEqual(ids.count, Set(ids).count, "stream ids must be unique")
    }
```

- [ ] **Step 3: Run the tests to verify they fail to compile**

Run:
```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' \
  -only-testing:iosAppTests/StreamCatalogTests 2>&1 | tail -25
```
Expected: build failure — `value of type 'Stream' has no member 'isLive'`.

- [ ] **Step 4: Add `isLive` to `Stream` and append live entries**

In `iosApp/iosApp/Streams/Stream.swift`, replace the struct with:

```swift
struct Stream: Identifiable, Hashable {
    let id: UUID
    let title: String
    let url: URL
    let isLive: Bool

    init(title: String, urlString: String, isLive: Bool = false) {
        self.id = UUID()
        self.title = title
        // Force-unwrap is acceptable for a hard-coded, reviewed demo catalog.
        self.url = URL(string: urlString)!
        self.isLive = isLive
    }
}
```

Then append the live entries to the end of the `demoStreams` array (before the closing `]`), keeping only URLs that returned `200` in Step 1:

```swift
    Stream(
        title: "Akamai — Live Test (DVR)",
        urlString: "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
        isLive: true
    ),
    Stream(
        title: "Unified Streaming — Live Demo",
        urlString: "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8",
        isLive: true
    ),
```

Also update the catalog doc comment: change "HLS-only by design (Phase 5 scope): no DASH/MP4/DRM entries" guidance is still true, but note VOD + live now coexist (one line).

- [ ] **Step 5: Run the catalog tests to verify they pass**

Run the Step 3 command again.
Expected: `Test Suite 'StreamCatalogTests' passed` — all tests (the 4 existing + 3 new) green. The existing `test_allStreams_areHlsM3U8` and `test_allStreams_haveHttpsUrls` still pass because the live URLs are `.m3u8` HTTPS.

- [ ] **Step 6: Commit**

```bash
git add iosApp/iosApp/Streams/Stream.swift iosApp/iosAppTests/StreamCatalogTests.swift
git commit -m "feat(ios): add isLive flag and live HLS entries to demo catalog"
```

---

## Task 2: `PlayerEngine` — DVR seekable-range publisher

**Files:**
- Modify: `iosApp/iosApp/Player/PlayerEngine.swift`
- Modify: `iosApp/iosAppTests/MockPlayerEngine.swift`

**Interfaces:**
- Produces: `PlayerEngine.seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never>` (publishes `nil` when no DVR window). `MockPlayerEngine.seekableRangeSubject: CurrentValueSubject<ClosedRange<TimeInterval>?, Never>` (default `nil`) for tests to drive.
- Consumes: nothing new.

> No standalone unit test — this task is verified by a clean build of the test target and exercised by Task 3's view-model tests.

- [ ] **Step 1: Add the protocol requirement**

In `iosApp/iosApp/Player/PlayerEngine.swift`, add to the `PlayerEngine` protocol (after `bufferedFractionPublisher`):

```swift
    /// The DVR/seekable window as `start...end` (absolute presentation seconds), or `nil` when no
    /// seekable range is available yet (VOD before ready, or live with no DVR window).
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> { get }
```

- [ ] **Step 2: Implement it in `AVPlayerEngine`**

Add the subject alongside the other subjects:

```swift
    private let seekableRangeSubject = CurrentValueSubject<ClosedRange<TimeInterval>?, Never>(nil)
```

Add the publisher alongside the others:

```swift
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> {
        seekableRangeSubject.eraseToAnyPublisher()
    }
```

In the periodic time-observer closure inside `init()`, add a call after `self.updateBufferedFraction()`:

```swift
            self.updateSeekableRange()
```

In `load(url:)`, after `durationSubject.send(0)`, reset the range so the new item doesn't briefly publish the previous window:

```swift
        seekableRangeSubject.send(nil)
```

Add the helper next to `updateBufferedFraction()`:

```swift
    private func updateSeekableRange() {
        guard let item = player.currentItem,
              let range = item.seekableTimeRanges.last?.timeRangeValue else {
            seekableRangeSubject.send(nil)
            return
        }
        let start = range.start.seconds
        let end = CMTimeRangeGetEnd(range).seconds
        guard start.isFinite, end.isFinite, end > start else {
            seekableRangeSubject.send(nil)
            return
        }
        seekableRangeSubject.send(start...end)
    }
```

- [ ] **Step 3: Conform `MockPlayerEngine`**

In `iosApp/iosAppTests/MockPlayerEngine.swift`, add the subject with the other subjects:

```swift
    let seekableRangeSubject = CurrentValueSubject<ClosedRange<TimeInterval>?, Never>(nil)
```

And the publisher with the others:

```swift
    var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> {
        seekableRangeSubject.eraseToAnyPublisher()
    }
```

- [ ] **Step 4: Build the test target to verify conformance**

Run (build only, no tests yet — confirms both `PlayerEngine` conformers compile):
```bash
xcodebuild build-for-testing -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' 2>&1 | tail -20
```
Expected: `** TEST BUILD SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git add iosApp/iosApp/Player/PlayerEngine.swift iosApp/iosAppTests/MockPlayerEngine.swift
git commit -m "feat(ios): expose DVR seekable-range publisher on PlayerEngine"
```

---

## Task 3: `PlayerViewModel` — live state, DVR scrub math, live labels

**Files:**
- Modify: `iosApp/iosApp/Player/PlayerViewModel.swift`
- Test: `iosApp/iosAppTests/PlayerViewModelTests.swift`

**Interfaces:**
- Consumes: `engine.seekableRangePublisher` (Task 2); `MockPlayerEngine.seekableRangeSubject` (Task 2).
- Produces: `PlayerViewModel.isLive: Bool`; `seekableRange: ClosedRange<TimeInterval>?`; `attach(streamURL:autoPlay:isLive:)` (new `isLive` param, default `false`); `scrubUpperBound: Double`; `scrubValue: Double`. `seekForward()`/`seekBack()`/`commitScrub(to:)`/`positionText`/`remainingText` become live-aware.

- [ ] **Step 1: Write the failing live tests**

Append to `iosApp/iosAppTests/PlayerViewModelTests.swift` (inside the class). Helper + cases:

```swift
    private func attachLive() {
        vm.attach(streamURL: URL(string: "https://example.com/live.m3u8")!,
                  autoPlay: false, isLive: true)
    }

    func test_attach_setsIsLive() {
        attachLive()
        XCTAssertTrue(vm.isLive)
    }

    func test_seekForward_live_clampsToSeekableRange() {
        attachLive()
        engine.seekableRangeSubject.send(100...200)
        engine.currentTimeSubject.send(195)
        vm.seekForward()
        XCTAssertEqual(engine.seekTargets.last, 200) // 195 + 10 clamped to upper bound
    }

    func test_seekBack_live_clampsToSeekableRange() {
        attachLive()
        engine.seekableRangeSubject.send(100...200)
        engine.currentTimeSubject.send(105)
        vm.seekBack()
        XCTAssertEqual(engine.seekTargets.last, 100) // 105 - 10 clamped to lower bound
    }

    func test_positionText_live_showsDVROffset() {
        attachLive()
        engine.seekableRangeSubject.send(100...200)
        engine.currentTimeSubject.send(165) // 65s into the DVR window
        XCTAssertEqual(vm.positionText, "01:05")
    }

    func test_remainingText_live_showsTimeToLiveEdge() {
        attachLive()
        engine.seekableRangeSubject.send(100...200)
        engine.currentTimeSubject.send(140) // 60s behind the live edge
        XCTAssertEqual(vm.remainingText, "-01:00")
    }

    func test_commitScrub_live_translatesDvrRelativeToAbsolute() {
        attachLive()
        engine.seekableRangeSubject.send(100...200)
        vm.beginScrub()
        vm.commitScrub(to: 30) // 30s into DVR window -> absolute 130
        XCTAssertEqual(engine.seekTargets.last, 130)
    }

    func test_live_nilSeekableRange_formattersFallBack() {
        attachLive()
        engine.currentTimeSubject.send(50) // no seekableRange sent -> nil
        XCTAssertEqual(vm.positionText, "00:00")
        XCTAssertEqual(vm.remainingText, "-00:00")
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' \
  -only-testing:iosAppTests/PlayerViewModelTests 2>&1 | tail -25
```
Expected: build failure — `extra argument 'isLive' in call` / `value of type 'PlayerViewModel' has no member 'isLive'`.

- [ ] **Step 3: Add live state + subscription to `PlayerViewModel`**

In `iosApp/iosApp/Player/PlayerViewModel.swift`, add published properties after `scrubState`:

```swift
    @Published private(set) var isLive = false
    @Published private(set) var seekableRange: ClosedRange<TimeInterval>?
```

In `init(engine:)`, after the `bufferedFractionPublisher` sink, add:

```swift
        engine.seekableRangePublisher.sink { [weak self] in self?.seekableRange = $0 }
            .store(in: &cancellables)
```

- [ ] **Step 4: Make `attach` accept `isLive`**

Replace `attach`:

```swift
    func attach(streamURL: URL, autoPlay: Bool, isLive: Bool = false) {
        self.isLive = isLive
        engine.load(url: streamURL)
        if autoPlay { engine.play() }
    }
```

- [ ] **Step 5: Make seek + scrub live-aware**

Replace `seekForward`, `seekBack`, and `commitScrub`:

```swift
    func seekForward() {
        if isLive {
            let upper = seekableRange?.upperBound ?? (currentTime + Self.seekStep)
            engine.seek(to: min(upper, currentTime + Self.seekStep))
            return
        }
        engine.seek(to: min(duration, currentTime + Self.seekStep))
    }

    func seekBack() {
        let lower = isLive ? (seekableRange?.lowerBound ?? 0) : 0
        engine.seek(to: max(lower, currentTime - Self.seekStep))
    }

    func commitScrub(to seconds: TimeInterval) {
        if isLive {
            let lower = seekableRange?.lowerBound ?? 0
            let absolute = lower + seconds                 // scrubber value is DVR-relative
            let upper = seekableRange?.upperBound ?? absolute
            let clamped = min(max(lower, absolute), upper)
            currentTime = clamped
            engine.seek(to: clamped)
            scrubState = .idle
            return
        }
        let clamped = min(max(0, seconds), duration > 0 ? duration : seconds)
        currentTime = clamped
        engine.seek(to: clamped)
        scrubState = .idle
    }
```

- [ ] **Step 6: Add scrubber coordinate helpers + live labels**

Add computed properties (near `positionText`):

```swift
    /// Upper bound for the scrubber's value space. Live uses DVR-window length (0-based);
    /// VOD uses absolute duration.
    var scrubUpperBound: Double {
        if isLive { return seekableRange.map { $0.upperBound - $0.lowerBound } ?? 1 }
        return Swift.max(duration, 1)
    }

    /// Current scrubber value in its own coordinate space: DVR-relative for live, absolute for VOD.
    var scrubValue: Double {
        if isLive { return currentTime - (seekableRange?.lowerBound ?? 0) }
        return currentTime
    }
```

Replace `positionText` and `remainingText`:

```swift
    var positionText: String {
        if isLive {
            guard let range = seekableRange else { return Self.mmss(0) }
            return Self.mmss(currentTime - range.lowerBound)
        }
        return Self.mmss(currentTime)
    }

    var remainingText: String {
        if isLive {
            guard let range = seekableRange else { return "-" + Self.mmss(0) }
            return "-" + Self.mmss(max(0, range.upperBound - currentTime))
        }
        return "-" + Self.mmss(max(0, duration - currentTime))
    }
```

- [ ] **Step 7: Run all view-model tests to verify they pass**

Run the Step 2 command again.
Expected: `Test Suite 'PlayerViewModelTests' passed` — the 7 new live tests plus all 9 pre-existing tests green. In particular `test_seekForward_addsTenSeconds_clampedToDuration`, `test_seekBack_subtractsTenSeconds_clampedToZero`, and `test_positionText_and_remainingText_format_mmss` still pass (VOD path unchanged, `isLive` defaults false).

- [ ] **Step 8: Commit**

```bash
git add iosApp/iosApp/Player/PlayerViewModel.swift iosApp/iosAppTests/PlayerViewModelTests.swift
git commit -m "feat(ios): add live/DVR state and scrub math to PlayerViewModel"
```

---

## Task 4: UI — live badge, hidden seek±10, DVR scrubber

**Files:**
- Modify: `iosApp/iosApp/Streams/StreamSelectionScreen.swift`
- Modify: `iosApp/iosApp/Player/PlayerControlsView.swift`
- Modify: `iosApp/iosApp/Player/PlayerScreen.swift`

**Interfaces:**
- Consumes: `Stream.isLive` (Task 1); `viewModel.isLive`, `viewModel.scrubUpperBound`, `viewModel.scrubValue`, `viewModel.positionText`, `viewModel.remainingText` (Task 3).
- Produces: no new public symbols (view-internal changes only).

> SwiftUI views are verified by a successful build and the existing `DemoFlowUITests` golden path (which uses a VOD stream and the unchanged code path).

- [ ] **Step 1: `StreamRow` — LIVE vs HLS badge**

In `iosApp/iosApp/Streams/StreamSelectionScreen.swift`, replace the private `StreamRow` struct with:

```swift
/// One stream row: title + a type badge — green "HLS" for VOD, red "● LIVE" for live.
private struct StreamRow: View {
    let stream: Stream
    var body: some View {
        HStack {
            Text(stream.title).foregroundColor(.white).fontWeight(.medium)
            Spacer()
            if stream.isLive {
                badge(text: "● LIVE", color: .red)
            } else {
                badge(text: "HLS", color: Color(red: 0.19, green: 0.82, blue: 0.35))
            }
        }
        .padding(.vertical, 6)
    }

    private func badge(text: String, color: Color) -> some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundColor(color)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(color.opacity(0.2))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
```

- [ ] **Step 2: `PlayerScreen` — pass `isLive` into attach**

In `iosApp/iosApp/Player/PlayerScreen.swift`, in `start()`, replace the `attach` call:

```swift
        viewModel.attach(streamURL: stream.url, autoPlay: settings.autoPlay, isLive: stream.isLive)
```

- [ ] **Step 3: `PlayerControlsView` — hide seek±10 when live**

In `iosApp/iosApp/Player/PlayerControlsView.swift`, replace the center transport `HStack` with:

```swift
            // Center transport row. Seek ±10s is hidden for live (no meaning at a live edge).
            HStack(spacing: 28) {
                if !viewModel.isLive {
                    transportButton("gobackward.10", id: A11y.Player.seekBack, action: viewModel.seekBack)
                }
                transportButton(viewModel.isPlaying ? "pause.fill" : "play.fill",
                                id: A11y.Player.playPause, size: 34, action: viewModel.togglePlayPause)
                if !viewModel.isLive {
                    transportButton("goforward.10", id: A11y.Player.seekForward, action: viewModel.seekForward)
                }
            }
```

- [ ] **Step 4: `PlayerControlsView` — LIVE badge in place of position label**

Replace the bottom `HStack` of position/remaining labels with:

```swift
                    HStack {
                        if viewModel.isLive {
                            liveBadge.accessibilityIdentifier(A11y.Player.positionLabel)
                        } else {
                            Text(viewModel.positionText)
                                .accessibilityIdentifier(A11y.Player.positionLabel)
                        }
                        Spacer()
                        Text(viewModel.remainingText)
                            .accessibilityIdentifier(A11y.Player.durationLabel)
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundColor(.white)
```

Add the `liveBadge` view as a private computed property on `PlayerControlsView` (after `body`):

```swift
    private var liveBadge: some View {
        HStack(spacing: 4) {
            Circle().fill(Color.red).frame(width: 7, height: 7)
            Text("LIVE").font(.caption.weight(.bold)).foregroundColor(.white)
        }
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(Color.red.opacity(0.25))
        .clipShape(Capsule())
    }
```

- [ ] **Step 5: `ScrubberView` — DVR-relative coordinate space**

In the private `ScrubberView` struct, change the `upperBound` derivation and the slider binding to read the view-model's coordinate-agnostic helpers. Replace:

```swift
        let upperBound = max(viewModel.duration, 1)
```
with:
```swift
        let upperBound = max(viewModel.scrubUpperBound, 1)
```

And in the `Slider`, replace the `get` and the `onEditingChanged` begin-branch so both use `scrubValue` (not `currentTime`):

```swift
                value: Binding(
                    get: { viewModel.scrubState == .scrubbing ? dragValue : viewModel.scrubValue },
                    set: { dragValue = $0 }
                ),
                in: 0...upperBound,
                onEditingChanged: { editing in
                    if editing {
                        dragValue = viewModel.scrubValue
                        viewModel.beginScrub()
                    } else {
                        viewModel.commitScrub(to: dragValue)
                    }
                }
```

(The buffered-fill capsule still reads `viewModel.bufferedFraction`, which stays `0` for live — it renders at width 0, which is correct.)

- [ ] **Step 6: Build the app + run the existing UI test to verify no regression**

Run:
```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' \
  -only-testing:iosAppUITests/DemoFlowUITests 2>&1 | tail -25
```
Expected: `Test Suite 'DemoFlowUITests' passed` — the golden path (select first/VOD stream → play) is unaffected.

- [ ] **Step 7: Commit**

```bash
git add iosApp/iosApp/Streams/StreamSelectionScreen.swift \
        iosApp/iosApp/Player/PlayerControlsView.swift \
        iosApp/iosApp/Player/PlayerScreen.swift
git commit -m "feat(ios): adapt player UI for live streams (LIVE badge, DVR scrubber)"
```

---

## Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire iosApp test bundle**

Run:
```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 14' 2>&1 | tail -30
```
Expected: `** TEST SUCCEEDED **` — `StreamCatalogTests`, `PlayerViewModelTests`, `SettingsStoreTests`, and `DemoFlowUITests` all pass.

- [ ] **Step 2: Manual smoke check (optional but recommended)**

Launch the app in the simulator, select a live entry (Akamai/Unified), and confirm: the row shows a red "● LIVE" badge; in the player the bottom-left shows the LIVE pill, the seek ±10 s buttons are absent, and the scrubber thumb sits near the right edge (live edge). Drag the scrubber left and confirm playback seeks back into the DVR window.

- [ ] **Step 3: Copy the plan + spec into the main checkout (housekeeping)**

The worktree is merged back at branch-completion; no extra commit needed here.

---

## Self-Review Notes

- **Spec §1 (model/catalog):** Task 1. ✓
- **Spec §2 (engine/view-model, incl. nil-while-live fallbacks, begin-scrub coordinate fix, absolute-time seek):** Tasks 2–3, with explicit `nil`-range fallbacks in `seekForward`/`seekBack`/`commitScrub`/`positionText`/`remainingText` and `dragValue = viewModel.scrubValue` on begin-scrub. ✓
- **Spec §3 (UI: LIVE badge, hidden seek±10, DVR scrubber, ScrubberView coordinate-agnostic):** Task 4. ✓
- **Spec §4 (tests, incl. `test_live_nilSeekableRange_formattersFallBack`):** Tasks 1 & 3; UI golden path reused (Task 4 Step 6). ✓
- **Known limitation (paused window doesn't slide):** inherent to `addPeriodicTimeObserver`; documented in spec, no code needed. ✓
- **Hyphen consistency:** all live labels use ASCII `-` to match existing VOD `remainingText`. ✓
