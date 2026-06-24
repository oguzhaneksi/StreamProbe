# iOS Demo — HLS Live Streams Design

**Date:** 2026-06-24
**Branch:** feature/ios-live-streams (to be created)
**Scope:** iOS demo app only (`iosApp/`). No changes to the SDK, Android app, or KMP common code.

---

## Goal

Add HLS live streams to the iOS demo app and adapt the player UI so live content is presented correctly: a DVR scrubber for seeking within the live window, a "● LIVE" badge replacing the position timestamp, and the seek ±10 s buttons hidden (irrelevant at a live edge).

---

## Section 1 — Stream Model & Catalog

### `Stream` struct (`iosApp/iosApp/Streams/Stream.swift`)

Add `isLive: Bool = false` as a stored property.

```swift
struct Stream: Identifiable, Hashable {
    let id: UUID
    let title: String
    let url: URL
    let isLive: Bool

    init(title: String, urlString: String, isLive: Bool = false) {
        self.id = UUID()
        self.title = title
        self.url = URL(string: urlString)!
        self.isLive = isLive
    }
}
```

All existing VOD entries keep `isLive` at its default (`false`) — no call-site changes needed.

### Live entries added to `demoStreams`

Two to three well-known public live HLS test streams are appended after the VOD block, marked `isLive: true`. Exact URLs are validated during implementation; candidates include:

- Akamai public live test — `https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8`
- Unified Streaming live demo — `https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8`

Both are `.m3u8` HTTPS URLs so the existing catalog tests continue to pass unchanged.

---

## Section 2 — `PlayerEngine` & `PlayerViewModel`

### `PlayerEngine` protocol addition (`iosApp/iosApp/Player/PlayerEngine.swift`)

```swift
var seekableRangePublisher: AnyPublisher<ClosedRange<TimeInterval>?, Never> { get }
```

Publishes the DVR window as `start...end` for live streams (from `AVPlayerItem.seekableTimeRanges.last`). Publishes `nil` when no seekable range exists (VOD before ready, or live with no DVR window).

### `AVPlayerEngine` implementation

- A `CurrentValueSubject<ClosedRange<TimeInterval>?, Never>` named `seekableRangeSubject` is reset to `nil` on every `load(url:)`.
- `updateSeekableRange()` is called inside the existing 0.5 s periodic time observer so the sliding live window refreshes continuously without an extra KVO subscription.

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

`bufferedFraction` already guards `item.duration.seconds.isFinite` — live streams (infinite duration) safely publish `0.0`, which is correct (no buffered-fill overlay on the DVR scrubber).

### `PlayerViewModel` changes (`iosApp/iosApp/Player/PlayerViewModel.swift`)

| Change | Detail |
|--------|--------|
| `@Published private(set) var isLive: Bool` | Set synchronously in `attach(streamURL:autoPlay:isLive:)` — no async-detection flash |
| `@Published private(set) var seekableRange: ClosedRange<TimeInterval>?` | Sunk from `seekableRangePublisher` |
| `attach(streamURL:autoPlay:isLive:)` | New `isLive` parameter (default `false`) |
| `seekForward()` | When live, clamps within `seekableRange` instead of `0...duration` |
| `seekBack()` | Same |
| `commitScrub(to:)` | Receives a DVR-relative value (0-based) when live; translates to absolute time as `seekableRange.lowerBound + dvrRelativeSeconds` before calling `engine.seek(to:)` |
| `positionText` | When live: `MM:SS` from DVR start (`currentTime − seekableRange.lowerBound`) |
| `remainingText` | When live: `"−MM:SS"` behind live edge (`seekableRange.upperBound − currentTime`) |

`scrubUpperBound` (new computed property used by the scrubber binding):
- Live: `seekableRange.map { $0.upperBound - $0.lowerBound } ?? 1`
- VOD: `max(duration, 1)`

`scrubValue` (for scrubber `get`/`set`):
- Live: `currentTime - (seekableRange?.lowerBound ?? 0)` — DVR-relative (0 = start of window)
- VOD: `currentTime`

`seekForward()` / `seekBack()` for live operate on **absolute** time: `engine.seek(to: min(seekableRange.upperBound, currentTime + seekStep))` and `engine.seek(to: max(seekableRange.lowerBound, currentTime - seekStep))`. These do not go through the DVR-relative coordinate space.

---

## Section 3 — UI

### `StreamRow` (`iosApp/iosApp/Streams/StreamSelectionScreen.swift`)

Badge switches on `stream.isLive`:

- **VOD:** existing green `"HLS"` capsule (unchanged)
- **Live:** red `"● LIVE"` capsule (same shape/padding, `Color.red` tint)

### `PlayerControlsView` (`iosApp/iosApp/Player/PlayerControlsView.swift`)

All branching is inline `if viewModel.isLive` — no new view types.

**Bottom bar:**

| Slot | VOD | Live |
|------|-----|------|
| Left label | `positionText` (white) | `"● LIVE"` badge (red capsule) |
| Scrubber | `0...max(duration, 1)` | `0...scrubUpperBound` |
| Right label | `remainingText` | `remainingText` (`"−MM:SS"`) |

**Center transport row:**

| VOD | Live |
|-----|------|
| seek −10s \| play/pause \| seek +10s | play/pause only |

**Buffering spinner:** unchanged (shown for both).

**`ScrubberView`** receives `upperBound: Double` and `value: Binding<Double>` from `PlayerControlsView` so the scrubber can work in both absolute (VOD) and DVR-relative (live) coordinate spaces without knowing which it is.

---

## Section 4 — Tests

### `MockPlayerEngine` (`iosApp/iosAppTests/MockPlayerEngine.swift`)

Add `seekableRangePublisher` returning `Just(nil).eraseToAnyPublisher()`.

### `StreamCatalogTests` additions

- `test_liveStreams_areTaggedIsLive()` — at least one entry has `isLive == true`
- `test_vodStreams_areNotTaggedIsLive()` — the existing 5 VOD entries remain `isLive == false`
- `test_allStreams_haveUniqueIds()` — documents the UUID-uniqueness contract (already holds by construction)

### `PlayerViewModelTests` additions

All use `MockPlayerEngine` (no network):

- `test_attach_setsIsLive()` — `attach(…isLive: true)` sets `viewModel.isLive = true`
- `test_seekForward_live_clampsToSeekableRange()` — seek forward from live edge stays within range
- `test_seekBack_live_clampsToSeekableRange()` — seek back from DVR start stays at lower bound
- `test_positionText_live_showsDVROffset()` — correct `MM:SS` from DVR start
- `test_remainingText_live_showsTimeToLiveEdge()` — correct `"−MM:SS"` format

### UI tests

No additions. The existing `DemoFlowUITests` taps the first row and plays; live streams follow the same code path so the golden-path test still covers the launch → select → play flow.

---

## Invariants & Non-goals

- SDK code (`sdk/`) is untouched.
- Android demo app is untouched.
- FairPlay DRM live streams are out of scope.
- DVR window size is not displayed; the scrubber length implicitly represents it.
- The "● LIVE" badge is not tappable (informational only).
