# The symptom lies: diagnosing five identical-looking streaming faults

> Draft for an external post (Medium / LinkedIn). Source lives in the repo at
> `tools/fault-deck/case-study/`. Every screenshot and terminal block below is a
> real, unedited capture from the rig — no doctored numbers. The *faults*, by
> contrast, are deliberately injected so the ground truth is known: the throttle
> is a real byte-rate cap, and the CDN case stamps synthetic `MISS` cache headers
> (there's no real CDN in the loop). That's the test bench, not a sleight of hand.

A user reports: *"the video is playing at low quality."*

That one sentence is the entire bug report you get in the real world. And it is almost useless, because at least five completely different root causes produce exactly that symptom:

- the stream's manifest was trimmed and the high rungs were never offered;
- the network link genuinely can't sustain the top rung;
- the CDN is missing cache on every segment;
- the player is hard-capped to a low resolution by its own configuration;
- the player's ABR is mistuned and refuses to climb even though it could.

Same video, same blurry picture. Five different fixes — in five different teams (encoding, networking, CDN, client). Picking the wrong one costs days.

This is a walkthrough of how long it takes to tell these apart **with raw tools** (`curl`, `adb logcat`) versus **with an in-player diagnostics overlay** ([StreamProbe](https://github.com/oguzhaneksi/StreamProbe)). I'll be honest about where the overlay wins big, and equally honest about the one place it still can't tell the whole story.

## The setup

To make this reproducible and not hand-wavy, every case below runs against a controlled rig:

- One real HLS ladder — 240p / 480p / 720p (AVC) + 1080p (HEVC) — encoded once.
- An nginx server that exposes the *same* ladder under several profiles: the full manifest, a manifest trimmed to 480p, a byte-rate-throttled path, and a path that stamps fake `X-Cache: MISS` headers on every response.
- An Android demo app that plays a given URL and can mistune its own ExoPlayer track selector on command.

So each "fault" is a known, injected condition. We know the right answer — the question is how fast each approach *gets* to it. (The rig is in the repo if you want to run it yourself.)

The whole point: in all five cases below, the picture on screen looks the same — low quality. Everything that follows is about telling them apart.

## Methodology

A few words on how this was run, so the comparison is fair rather than rigged:

- **Who:** one engineer familiar with HLS/ABR and comfortable with `curl` and `adb` — so the raw-tools arm is a *competent* baseline, not a strawman who doesn't know where to look.
- **Tools allowed in the raw arm:** the video itself, `curl`, and `adb logcat`. No source-code access, except for the two configuration faults where reading the player setup is the only raw path that exists.
- **Tools in the overlay arm:** the StreamProbe overlay only, attached to the same player playing the same URL.
- **What was measured:** per fault — the number of distinct tools touched, the number of commands/actions taken, the number of context switches (app ↔ terminal ↔ editor), and an approximate wall-clock time. Time is reported as a range, not a stopwatch figure; it varies too much by operator to quote precisely.
- **Ground truth:** each fault is an injected condition with a known cause. The cause was revealed only *after* the diagnosis was written down, so the raw-tools arm couldn't shortcut to the answer.

---

## Case 1 — `manifest_cap`: the cause is in the manifest, not the network

**Symptom:** video tops out at 480p and never climbs.

### Without the tool

Your first instinct is "slow network." But before chasing that, you check the stream itself — you pull the manifest the player was actually served:

```text
$ curl -s http://localhost:8080/cap480/master.m3u8     # what the player was served
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-STREAM-INF:BANDWIDTH=492000,RESOLUTION=426x240,CODECS="avc1.4d401e,mp4a.40.2"
240p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1380000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
480p/index.m3u8

$ curl -s http://localhost:8080/full/master.m3u8       # what the source actually has
...
#EXT-X-STREAM-INF:BANDWIDTH=3510000,RESOLUTION=1280x720,...
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=8560000,RESOLUTION=1920x1080,CODECS="hvc1.2.4.L120.90,..."
1080p/index.m3u8
```

The served manifest lists only 240p and 480p. The 720p and 1080p rungs exist at the source but were never advertised. **The cap is in the manifest, not the network.**

That's the right answer — but getting there meant: knowing to suspect the stream, finding the manifest URL, `curl`-ing it, reading `EXT-X-STREAM-INF` lines, and ideally comparing against an unfiltered reference to be sure rungs are *missing* rather than simply absent.

### With StreamProbe

![manifest_cap — Tracks tab caps at 480p, throughput is high](assets/manifest_cap-tracks.png)

The Tracks tab lists the runtime ladder — every rung the player actually knows about: **426×240 and 854×480, and nothing above.** The header shows throughput at **39 MB/s** (`TP: 39.0 MB/s`), so the link is plainly fast. A runtime ladder that stops at 480p next to high throughput points at the manifest, not the network — both facts colocated in the player-visible state, no external lookup required.

**Difference:** several `curl`s plus manifest parsing, versus two colocated numbers the player already holds.

---

## Case 2 — `network_throttle`: the cause genuinely *is* the network

**Symptom:** identical. Video tops out below the ceiling and never climbs. This is the deliberate inverse of Case 1.

### Without the tool

The manifest checks out this time (full ladder present), so you measure the link by fetching the same segment over the real path and an unthrottled reference:

```text
$ curl -o /dev/null -s -w '%{speed_download} B/s  in %{time_total}s\n' \
    http://localhost:8080/full/720p/seg_001.ts
64482208 B/s  in 0.020572s        # ~64 MB/s on the unthrottled path

$ curl -o /dev/null -s -w '%{speed_download} B/s  in %{time_total}s\n' \
    http://localhost:8080/throttle/720p/seg_001.ts
654261 B/s  in 2.027521s          # ~654 KB/s on the throttled path
```

Same segment, ~100× slower on the real path — 2 seconds instead of 20 milliseconds. **The link is the bottleneck**, so ABR is correctly sitting below the top rung. Right answer again, but it took knowing which segment to fetch, fetching from two paths, and reasoning about whether the measured rate sustains the target bitrate.

### With StreamProbe

![network_throttle — full ladder present, but every segment crawls in](assets/network_throttle-tracks.png)

Same Tracks tab, opposite story. The full ladder is present — **720p sits in the selected pool (green), 1080p greyed** — but look at the latest segment: **`DL: 2031 ms`** for a single chunk, at **`TP: 927.5 KB/s`**. That's the same two-seconds-per-segment the `curl` measured, surfaced live in the player: each chunk crawls in, throughput stays under 1 MB/s — below the ~1.07 MB/s the 1080p rung needs sustained — so ABR holds a lower rung (it hovers around 480–720p). The rungs exist; the link can't feed them. The exact inverse of Case 1, where the rungs were missing and the link was fast.

> Throughput is the noisier signal here: the player opens parallel connections, so the headline rate wobbles run to run (~0.65–1.3 MB/s). The robust tell is the **download time per segment** — one to two seconds, versus ~20 ms unthrottled — which is precisely what the `curl` timing showed.

**Difference:** two `curl` measurements plus bitrate-vs-rate reasoning, versus a per-segment download time the player surfaces as it plays.

---

## Case 3 — `cdn_miss`: quality is fine; the problem is the CDN

**Symptom:** the trickiest of the set, because the rendered quality is actually *fine*. Users almost never report "my CDN cache is cold" — they report the *experience* ("it's laggy," "it keeps buffering," "the quality is bad"), so a cache problem reaches you disguised as a quality complaint. The picture looks okay, yet playback feels heavier than it should and nothing in the manifest or the bitrate explains why.

### Without the tool

There's nothing to see in the manifest or in throughput, so you have to suspect the CDN and dump response headers:

```text
$ curl -sI http://localhost:8080/cdnmiss/480p/seg_001.ts | grep -iE 'x-cache|cf-cache'
X-Cache: MISS
CF-Cache-Status: MISS

$ curl -sI http://localhost:8080/full/480p/seg_001.ts | grep -iE 'x-cache|cf-cache'
(no cache headers present)
```

Every segment is a cache **MISS** — each request goes all the way to origin instead of being served from the edge. Quality is unaffected, but every segment pays origin latency. To get here you had to suspect the CDN at all, know to dump headers, and recognize cache-status header names across vendors.

### With StreamProbe

![cdn_miss — every segment flagged MISS, quality unaffected](assets/cdn_miss-segments.png)

The header says it outright — **`CDN STATUS: [CLOUDFLARE] ○ MISS · X-Cache: MISS`** — and the Segments tab flags **every segment with a red MISS dot** while throughput stays high (2–6 MB/s per segment). Quality is fine; the cache is the story. The overlay parses the cache headers for you, across vendors, with no guessing which header to look for.

**Difference:** suspecting the CDN and knowing the header names, versus a status line that names the CDN and the cache state for you.

---

## The honest part: where the overlay *almost* failed

Here's the case I expected to be the overlay's blind spot — and the surprise is the most interesting result in this whole exercise.

Two more faults produce the same low-quality symptom:

- **`constrained`** — the player is hard-capped to 480p by `setMaxVideoSize(854, 480)`.
- **`bw_misconfig`** — the player's ABR is mistuned with a tiny `bandwidthFraction`, so it refuses to climb.

In both, the full ladder is present and the link is fast. The player simply chooses not to climb. I assumed the overlay couldn't tell them apart — it reports what the player *decided*, not *why*. So I put them side by side expecting two identical screenshots:

| `constrained` (hard size cap) | `bw_misconfig` (mistuned ABR) |
|---|---|
| ![constrained](assets/constrained-tracks.png) | ![bw_misconfig](assets/bw_misconfig-tracks.png) |

They are **not** identical.

Look at the **720p rung**. Under `constrained` it's **greyed out** — `setMaxVideoSize` removed it from the adaptive pool entirely, so the player can't even consider it (pool = 240p, 480p). Under `bw_misconfig` the **720p rung is green** — it's in the adaptive pool, fully available, the ABR algorithm just won't climb to it (and it sits even lower, at 240p). The overlay colors each rung by whether it's in the player's current selection pool (`Tracks.Group.isTrackSelected`), and that single visual cue separates a *capped* ladder from a *mistuned* climb. The tool distinguishes a case I was sure it couldn't.

**And the limits that remain** — being equally honest — sit one level deeper. The overlay shows *that* `constrained` narrowed the pool to 480p, but not *why*. And the 1080p rung is greyed in **both** shots for a reason the overlay can't name: here it's HEVC Main10 that this device can't decode (*unsupported*), not a config *deselection*. Same grey dot, two different meanings.

### Roadmap: two gaps this surfaced

These aren't footnotes to bury — they're the most credible part of the writeup, because they're the tool's own limits, found by using it on a case it was built for:

1. **Surface `isSupported` on `VariantInfo`.** Today a greyed rung means either "excluded by config" or "undecodable by this hardware," and the overlay can't tell you which. Media3 already exposes `Tracks.Group.isTrackSupported(i)`; plumbing that into `VariantInfo` would let the overlay mark a rung "present but undecodable" and resolve the ambiguity on the 1080p HEVC rung directly.
2. **Expose the relevant `TrackSelectionParameters`.** The overlay reports *that* the selected pool was narrowed but not *why* — a max-size cap, a max-bitrate cap, and a low bandwidth-fraction all look alike from the outside. Surfacing the active constraints would turn "the player selected 480p" into "the player selected 480p *because* of constraint Y," and would have separated `constrained` from `bw_misconfig` by cause, not just by symptom.

Neither is implemented yet. Both are honest gaps in player-visible state, and naming them is the point — a diagnostics tool you can't trust to admit what it *can't* see isn't a diagnostics tool.

---

## Scorecard

Time-to-diagnose isn't deterministic — it depends heavily on who's debugging and how well they know HLS internals — so the primary comparison is the number of moving parts each path takes: distinct **tools**, **commands/actions**, and **context switches**. Approximate wall-clock time is a secondary note, quoted for the *competent* operator from the methodology above; an unfamiliar one takes substantially longer or stalls entirely.

**Raw tools (`curl`, `adb logcat`, source):**

| Fault | Tools | Commands / actions | Context switches | Approx. time |
|---|---|---|---|---|
| `manifest_cap` | `curl` | ~4 — locate manifest URL, `curl` served + reference, diff `EXT-X-STREAM-INF` | 2 (player ↔ terminal) | 2–5 min |
| `network_throttle` | `curl` | ~4 — confirm manifest, fetch one segment on two paths, compare rate vs target bitrate | 2 (player ↔ terminal) | 3–7 min |
| `cdn_miss` | `curl` | ~3 — suspect CDN, `curl -I`, match cache-status header names | 2 (player ↔ terminal) | 5–15 min\* |
| `constrained` vs `bw_misconfig` | IDE / source | ~5 — open project, find player setup, read `TrackSelectionParameters` | 3 (player ↔ editor ↔ docs) | 10–30 min, source required |

\* slow because the operator first has to *suspect* the CDN at all — there's no on-screen prompt to. If that hypothesis never occurs, the bug stays open.

**StreamProbe overlay:**

| Fault | Tools | Commands / actions | Context switches | Approx. time |
|---|---|---|---|---|
| all five | overlay | 0–1 — switch to the relevant tab | 0 — diagnosis is in-player | seconds |

The overlay's advantage isn't raw speed — it's **evidence colocation**. The runtime ladder, the selected pool, per-segment throughput, and cache status all sit in the player-visible state, next to the playback they describe. The raw-tools arm reconstructs that same state from outside the player, one command at a time, and has to know which commands to run before it can even begin — which is exactly where the `cdn_miss` row bleeds time.

The honest takeaway isn't "the tool is magic." It's that surfacing the player-visible state ABR actually acts on — the runtime ladder, the selected pool, throughput, cache status — collapses "the video is low quality" from a five-way external investigation into reading state the player already holds. And it stays candid about the one thing that state doesn't yet include: the *why* behind a track-selection decision.

---

*StreamProbe is a debug-only diagnostics SDK for Media3/ExoPlayer (Android) and AVFoundation (iOS). It is never enabled in release builds. Repo: https://github.com/oguzhaneksi/StreamProbe*
