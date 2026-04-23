# StreamProbe

A debug SDK for Android apps that inspects HLS and DASH streaming traffic in real time on top of Media3/ExoPlayer, surfacing manifest contents, segment metrics, CDN headers, and ABR decisions through an in-app overlay.

> ⚠️ **Development-only tool.** StreamProbe is designed for debug builds and should be omitted from release builds by gating it behind `BuildConfig.DEBUG` or a similar host-managed mechanism. It is not an analytics, QoE, or production monitoring product.

---

## Why This Exists

When something goes wrong with stream delivery in an Android app — the wrong rendition gets picked, an unexpected ABR switch happens, segment latency spikes, or a CDN cache miss slips through — the developer's options today are all awkward:

- **Charles Proxy**: wire the device through a proxy, install an SSL cert, filter traffic by hand.
- **`adb logcat`**: sift through raw ExoPlayer logs looking for the relevant lines.
- **Manual manifest inspection**: copy the URL into a browser and read the `.m3u8` / MPD by eye.
- Usually, all three at once.

Each round of this takes 15–30 minutes, depends on a tethered device, requires external tools, and can't be handed off to a teammate. StreamProbe pulls the whole workflow into the app itself: the moment the SDK is attached, everything is captured automatically and readable through an on-screen overlay.

ExoPlayer's built-in `EventLogger` and `DebugTextViewHelper` only surface player events — they don't touch manifest contents, CDN headers, or segment timing. StreamProbe closes that gap.

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App (debug)                    │
│                                                             │
│   ┌──────────────────┐          ┌────────────────────┐      │
│   │  Media3 /        │          │  Debug Overlay UI  │      │
│   │  ExoPlayer       │◄─────────┤  (draggable panel) │      │
│   └────────┬─────────┘          └──────────▲─────────┘      │
│            │                               │                │
│            │ attach(player)                │ reads          │
│            ▼                               │                │
│   ┌─────────────────────────────────────┐  │                │
│   │         StreamProbe Core            │──┘                │
│   │                                     │                   │
│   │  ┌──────────────┐ ┌──────────────┐  │                   │
│   │  │ MediaSource  │ │   Network    │  │                   │
│   │  │   Factory    │ │  Inspector   │  │                   │
│   │  │   Wrapper    │ │  (Abstract)  │  │                   │
│   │  └──────┬───────┘ └──────┬───────┘  │                   │
│   └─────────┼────────────────┼──────────┘                   │
│             │                │                              │
│             ▼                ▼                              │
│     manifest parsing    segment timing +                    │
│     ABR switch events   CDN response headers                │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
                   HLS / DASH origin
                        + CDN
```

A single `AnalyticsListener` wired to the player feeds an in-memory session store that the overlay reads from:

- **Manifest info** — read from `ExoPlayer.currentManifest` on `onTimelineChanged`; all variant streams, codecs, resolutions, and bitrates are extracted into SDK-owned models.
- **Segment metrics and CDN headers** — captured on `onLoadCompleted`; per-segment download duration, size, throughput, and HTTP response headers (including cache hit/miss status) are stored for the session.
- **ABR switch events** — captured on `onDownstreamFormatChanged`; every quality change is recorded with the previous/new track, buffer state at switch time, and the reason Media3 reports (`INITIAL`, `ADAPTIVE`, `MANUAL`, `TRICKPLAY`, `UNKNOWN`). Events are kept in a capped chronological list and displayed in the overlay's ABR tab.

A `MediaSource.Factory` wrapper and a `NetworkInspector` abstraction (for OkHttp/Cronet/HttpEngine adapters enabling true TTFB capture) are planned for future milestones.

StreamProbe is distributed as a standard `implementation` dependency. Host apps guard the `attach()` calls behind `BuildConfig.DEBUG` to ensure zero runtime overhead in release builds.

---

## Roadmap

Coarse milestones. Each will be broken down into a TODO checklist as work begins.

- **M0 — Scaffolding** ✅: Gradle module, `implementation` distribution with host-managed gating, empty `attach` / `detach` API surface.
- **M1 — HLS MVP** ✅: Master playlist parsing, variant/rendition listing, basic overlay with active track display.
- **M2 — Segment & CDN** ✅: Per-segment timing (total duration, size, throughput) and CDN response header capture with cache hit/miss flagging. TTFB deferred to a future milestone via MediaSource.Factory wrapper.
- **M3 — ABR Log** ✅: Track switch event recording with buffer state, switch reason, and chronological timeline view in the overlay.
- **M4 — DASH Support** ✅: MPD parsing, feature parity with HLS across all prior milestones.
- **M5 — Distribution**: JitPack for early releases, then Maven Central for stable distribution.

---

## Known Limitations

- **Multi-period DASH:** Representations from all Periods are flattened into a single variant list. If the same Representation appears in multiple Periods (e.g., around ad boundaries), it will be listed multiple times. Period-aware grouping is a planned future enhancement.

---

## Spec

For the detailed feature breakdown, API surface, and technical design, see [`SPEC.md`](./SPEC.md).

---

## License

Apache License 2.0. See [`LICENSE`](./LICENSE) for details.