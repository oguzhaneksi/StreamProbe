# Fault Deck: Expected Overlay Fingerprints

This is the answer key. For each card it lists what the StreamProbe overlay
should look like. Draw a card with `./run-card.sh`, write down your guess by
reading the overlay, then run `./reveal.sh` and compare against this table.

All six cards share one visible symptom: the video plays at low quality. The
overlay tabs (Tracks, Segments, Switches, Errors) are how you tell them apart.

| Card | Tracks tab | Segments tab (throughput) | Switches tab | Errors tab |
|---|---|---|---|---|
| `manifest_cap` | 1080p **missing** (max rung is 480p) | normal | no upswitch (nothing to climb to) | clean |
| `constrained` | 1080p present, **not selected** | **high** (bandwidth headroom exists) | no upswitch, INITIAL only | clean |
| `bw_misconfig` | 1080p present, **not selected** | **high** (bandwidth headroom exists) | no upswitch | clean |
| `network_throttle` | 1080p present, not selected | **low** (link is the bottleneck) | early ADAPTIVE downswitch | clean |
| `cdn_miss` | normal | normal | normal | clean, but Segments show cache MISS |
| `decode_fail` | 1080p present, not selected | high | no upswitch | `onVideoCodecError` may appear |

## How to read each fingerprint

- **manifest_cap**: the server hides 1080p from the master playlist. The player
  never knew it existed, so there is nothing to climb to. Tracks tab caps at
  480p. Throughput is fine. The cap is in the manifest, not the network.

- **constrained**: the full ladder is present (1080p shows in Tracks) but the
  player is hard-capped to 480p by `setMaxVideoSize(854, 480)`. Throughput is
  high because the link is fast. The player simply chooses not to climb.

- **bw_misconfig**: identical visible story to `constrained`. The full ladder is
  present, throughput is high, but ABR uses a tiny bandwidth fraction (0.1) and
  stays cautious. See Known limitation 1.

- **network_throttle**: the link itself is slow (nginx `limit_rate`). Throughput
  in Segments is low. ABR correctly drops a rung or two early. This is the one
  card where low throughput is the honest cause.

- **cdn_miss**: quality is actually fine here. The tell is in Segments: every
  segment carries `X-Cache: MISS` / `CF-Cache-Status: MISS`. Use this card to
  practice reading CDN cache headers, not quality drops.

- **decode_fail**: the full ladder is present and the link is fast, so ABR climbs
  to the top rung, which is 1080p HEVC Main10. A device that cannot decode it
  surfaces a codec error in the Errors tab. See the reliability note below.

## decode_fail reliability note (be honest about this)

`decode_fail` depends on the test device's decoder. The top rung is 1080p HEVC
Main10 (10-bit) specifically because ExoPlayer's default track selector filters
out video larger than the display, so a 4K rung would never be selected on a
1080p screen and the card would silently do nothing.

Even at 1080p, behavior varies:

- If the device has no HEVC Main10 decoder, the track is reported unsupported and
  the player stays on a lower AVC rung. You may see no error at all, just a quiet
  cap. (This is exactly Known limitation 2.)
- If the device has only a slow software HEVC decoder, you may see dropped frames
  rather than a hard `onVideoCodecError`.
- If the device decodes it fine, re-run `make-ladder.sh` with `INCLUDE_4K=1` to
  add an even harder 2160p HEVC rung, or raise the bitrate.

Treat `onVideoCodecError` as the strong signal and dropped frames as the weak one.

## Known limitations (the most valuable findings of this case study)

These are honest limits of the tool, not bugs.

### 1. `constrained` and `bw_misconfig` look identical in the overlay

Both show the full ladder with 1080p present-but-not-selected, high throughput,
and no upswitch. The overlay reports what the player **decided** (its track
selection) but not **why** it decided so. The cause lives in
`TrackSelectionParameters` and the track selector configuration
(`setMaxVideoSize` vs a low `bandwidthFraction`), which the overlay does not
surface. So the overlay alone cannot separate these two cards. This is a limit of
what the tool observes, not a defect.

### 2. `decode_fail` and `constrained` can blur in the Tracks tab

Both can show "1080p exists but is not selected." The only clean separator is the
codec error in the Errors tab, and even that is device-dependent (see the
reliability note). The root issue: `VariantInfo` in the SDK exposes `isSelected`
but not `isSupported`. Media3 already exposes `Tracks.Group.isTrackSupported(i)`,
so adding an `isSupported` flag to `VariantInfo` would let the overlay mark a
rung as "present but undecodable" and resolve the ambiguity directly.

**Future feature suggestion (do not implement here):** add `isSupported` to
`VariantInfo`, sourced from `Tracks.Group.isTrackSupported(trackIndex)`. This
task does not touch the SDK.
