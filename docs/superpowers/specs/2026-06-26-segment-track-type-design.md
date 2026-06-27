# Segment Track-Type Distinction (mini feature) — Design

**Date:** 2026-06-26
**Branch:** `feature/segment-track-type`
**Status:** Approved

## Problem

The StreamProbe overlay shows a flat segment timeline with no indication of which
segments are video vs audio vs text. Charles Proxy on the Apple BipBop HLS stream
clearly shows interleaved `fileSequenceX.ts` (video) and `fileSequenceX.aac` (audio)
chunks; StreamProbe renders them in one undifferentiated list. This also makes the
per-segment download times look "irregular" because small audio chunks and large
video chunks are interleaved without distinction.

On Android the classification is already available but unused: Media3's
`MediaLoadData.trackType` (`C.TRACK_TYPE_VIDEO` / `C.TRACK_TYPE_AUDIO` /
`C.TRACK_TYPE_TEXT`) is delivered to `PlayerInterceptor.onLoadCompleted` and ignored —
only `dataType` is read (to filter non-media loads).

## Goal

Classify each captured segment as **VIDEO / AUDIO / TEXT / UNKNOWN** and surface it in
the overlay as a compact, color-coded badge next to the segment index. Alongside the
badge, show the segment's **file extension** (e.g. `ts`, `aac`, `m4s`) derived from the
URI — unlike `trackType`, the extension is available on **both platforms** because it
comes from `SegmentMetric.uri`, which Android and iOS both populate. It therefore gives
iOS a useful hint even while iOS `trackType` stays `UNKNOWN`.

## Non-goals (YAGNI)

- Filtering the timeline by track type (no filter UI / presenter filter state).
- iOS heuristic inference from URI extension (`.aac`/`.ts`/`.vtt`) — unreliable for
  fMP4/CMAF; explicitly out of scope.

> **Follow-up (2026-06-27):** the original "don't touch the latest-segment text" non-goal
> was reversed on review — the track type + extension now appear there too (see §9). The
> extension label's contrast was also raised from 40% to 70% white (see §6/§7).

## Platform asymmetry (key decision)

| Platform | Source | Track type available? |
|---|---|---|
| Android | `MediaLoadData.trackType` in `onLoadCompleted` | **Yes** — clean VIDEO/AUDIO/TEXT |
| iOS (access-log roll-up) | `accessLogSegmentMetric` | No — roll-up entries carry no track type |
| iOS (iOS 18 AVMetrics per-segment) | `avMetricsSegmentMetric` | No reliable media-type field |

**Decision:** single shared model. `trackType` lives on the common `SegmentMetric`.
Android fills the real value; iOS leaves it at the default `UNKNOWN`. iOS may be
improved later without changing the model. The badge is hidden for `UNKNOWN`, so iOS
visuals are unchanged for now while the plumbing is in place.

## Design

### 1. Model (`commonMain`)

- New enum in the `model/` package:
  ```kotlin
  enum class SegmentTrackType { VIDEO, AUDIO, TEXT, UNKNOWN }
  ```
- New field on `SegmentMetric`:
  ```kotlin
  val trackType: SegmentTrackType = SegmentTrackType.UNKNOWN
  ```
  The default is load-bearing: every existing construction site (both iOS mappers,
  all tests) keeps compiling unchanged, and iOS degrades to `UNKNOWN` automatically.

### 2. Android mapping (`PlayerInterceptor.onLoadCompleted`)

- A pure, top-level, unit-testable helper maps the Media3 int to the enum:
  ```kotlin
  internal fun segmentTrackTypeOf(trackType: Int): SegmentTrackType = when (trackType) {
      C.TRACK_TYPE_VIDEO -> SegmentTrackType.VIDEO
      C.TRACK_TYPE_AUDIO -> SegmentTrackType.AUDIO
      C.TRACK_TYPE_TEXT  -> SegmentTrackType.TEXT
      else               -> SegmentTrackType.UNKNOWN
  }
  ```
- `onLoadCompleted` passes `trackType = segmentTrackTypeOf(mediaLoadData.trackType)`
  when building the `SegmentMetric`.
- Note: muxed TS (video+audio in one segment) may report as `UNKNOWN`/`VIDEO`; BipBop
  is demuxed so it yields clean V/A. Acceptable.

### 3. iOS

No change. Neither `accessLogSegmentMetric` nor `avMetricsSegmentMetric` sets
`trackType`; the default `UNKNOWN` applies.

### 4. Presenter

No change. `OverlayViewState.segments` already carries the raw
`List<SegmentMetric>`, so `trackType` flows through automatically.

### 5. Badge label + extension (`commonMain` formatters — shared, testable)

```kotlin
fun segmentTrackBadge(trackType: SegmentTrackType): String? = when (trackType) {
    SegmentTrackType.VIDEO -> "V"
    SegmentTrackType.AUDIO -> "A"
    SegmentTrackType.TEXT  -> "T"
    SegmentTrackType.UNKNOWN -> null
}

// Query-string-safe extension from the URI; null when there is no real extension.
fun segmentExtension(uri: String): String? {
    val path = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
    if (!path.contains('.')) return null
    val ext = path.substringAfterLast('.')
    return ext.takeIf { it.isNotBlank() && it.length <= MAX_EXT_LEN }
}
```

Both added to `OverlayFormatters` so they are consistent across platforms and locked by
`commonTest`. `segmentExtension` strips query (`?`) and fragment (`#`) before reading
the last path segment, returns `null` when there is no `.` (extensionless / byte-range
URIs), and guards against absurdly long "extensions" via a small `MAX_EXT_LEN` constant
(e.g. 5) so a stray dot in a path doesn't yield a giant label. Color mapping for the badge stays
platform-side (no shared color type in common); the extension renders as neutral
secondary text on both platforms. The extension is platform-independent — it works on
iOS too, since it derives only from the captured `uri`.

### 6. Android UI (`SegmentTimelineItemView`)

- Add a small, color-coded badge view next to `indexView`, built programmatically.
- New color-coded pill drawable in `OverlayDrawables` (distinct colors for V/A/T).
- `bind()` shows the badge using `OverlayFormatters.segmentTrackBadge(metric.trackType)`;
  hidden (`GONE`) when the label is `null` (UNKNOWN).
- Next to the badge, a neutral/dimmed extension label from
  `OverlayFormatters.segmentExtension(metric.uri)`; hidden (`GONE`) when `null`.

### 7. iOS UI (Swift segment cell)

- Add the equivalent badge component next to the index in the segment cell.
- Driven by the same `segmentTrackBadge` value; hidden for `UNKNOWN`, so the badge stays
  invisible until iOS gains real track types.
- The dimmed extension label (same `segmentExtension`) **does** render on iOS whenever
  the URI carries one, so iOS still shows `ts`/`aac`/`m4s` next to the (hidden) badge.

### 8. Tests

- `commonTest`: `segmentTrackBadge` mapping for all four enum values; `SegmentMetric`
  default `trackType == UNKNOWN`. `segmentExtension` cases: plain (`…/seg3.ts` → `ts`),
  query string (`…/seg3.ts?token=x` → `ts`), fragment, extensionless (`…/segment` →
  `null`), and `(unknown)` URI → `null`.
- `androidHostTest` (`PlayerInterceptorTest`): video/audio/text loads map to the
  correct `trackType` on the produced `SegmentMetric`; the `segmentTrackTypeOf` helper
  for unknown/default ints → `UNKNOWN`.

## Data flow (unchanged shape, new field)

```
ExoPlayer onLoadCompleted(mediaLoadData.trackType)
  → segmentTrackTypeOf(int) → SegmentMetric.trackType
  → SessionStore → OverlayPresenter (raw segments) → OverlayViewState.segments
  → SegmentTimelineItemView badge  (label via OverlayFormatters.segmentTrackBadge)
                          + extension (via OverlayFormatters.segmentExtension(uri))
```

The extension path needs no new model field — it is derived on render from the
existing `SegmentMetric.uri`, identically on Android and iOS.

### 9. Latest-segment stat line (`commonMain` — shared, testable)

`OverlayFormatters.formatSegmentMetric` (the source of `OverlayViewState.latestSegmentText`,
rendered by Android `latestSegmentView` and iOS `StatsView`) now appends the track type and
extension to its first line, so the single highlighted "latest" segment shows the same
classification as the timeline rows. Because this is a plain (uncolored) `TextView`/`UILabel`,
the **full word** (`VIDEO`/`AUDIO`/`TEXT`) is used rather than the color-dependent single-letter
`segmentTrackBadge` (the mapping is inlined in `formatSegmentMetric` to keep `OverlayFormatters`
under the detekt `TooManyFunctions` limit). Both the word and the extension are omitted when
absent (UNKNOWN track type / extensionless URI), so the line degrades to `DL: 200ms` exactly as
before:

```
DL: 320ms  ·  VIDEO  ·  ts     (Android: real track type + extension)
DL: 110ms  ·  aac              (iOS: UNKNOWN track type, extension only)
DL: 320ms                      (no track type, no extension)
```

One shared formatter change covers both platforms — iOS needs no Swift change because it renders
the bridged `latestSegmentText` directly. Locked by `OverlayFormattingTest` (`commonTest`).
