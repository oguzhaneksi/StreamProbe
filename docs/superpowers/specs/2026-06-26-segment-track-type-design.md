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
the overlay as a compact, color-coded badge next to the segment index.

## Non-goals (YAGNI)

- Filtering the timeline by track type (no filter UI / presenter filter state).
- Adding track type to the header "latest segment" text.
- iOS heuristic inference from URI extension (`.aac`/`.ts`/`.vtt`) — unreliable for
  fMP4/CMAF; explicitly out of scope.

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

### 5. Badge label (`commonMain` formatter — shared, testable)

```kotlin
fun segmentTrackBadge(trackType: SegmentTrackType): String? = when (trackType) {
    SegmentTrackType.VIDEO -> "V"
    SegmentTrackType.AUDIO -> "A"
    SegmentTrackType.TEXT  -> "T"
    SegmentTrackType.UNKNOWN -> null
}
```

Added to `OverlayFormatters`. Both platforms call it so the label is consistent and
locked by `commonTest`. Color mapping stays platform-side (no shared color type in
common).

### 6. Android UI (`SegmentTimelineItemView`)

- Add a small, color-coded badge view next to `indexView`, built programmatically.
- New color-coded pill drawable in `OverlayDrawables` (distinct colors for V/A/T).
- `bind()` shows the badge using `OverlayFormatters.segmentTrackBadge(metric.trackType)`;
  hidden (`GONE`) when the label is `null` (UNKNOWN).

### 7. iOS UI (Swift segment cell)

- Add the equivalent badge component next to the index in the segment cell.
- Driven by the same `segmentTrackBadge` value; hidden for `UNKNOWN`, so iOS visuals
  are unchanged until iOS gains real track types.

### 8. Tests

- `commonTest`: `segmentTrackBadge` mapping for all four enum values; `SegmentMetric`
  default `trackType == UNKNOWN`.
- `androidHostTest` (`PlayerInterceptorTest`): video/audio/text loads map to the
  correct `trackType` on the produced `SegmentMetric`; the `segmentTrackTypeOf` helper
  for unknown/default ints → `UNKNOWN`.

## Data flow (unchanged shape, new field)

```
ExoPlayer onLoadCompleted(mediaLoadData.trackType)
  → segmentTrackTypeOf(int) → SegmentMetric.trackType
  → SessionStore → OverlayPresenter (raw segments) → OverlayViewState.segments
  → SegmentTimelineItemView badge  (label via OverlayFormatters.segmentTrackBadge)
```
