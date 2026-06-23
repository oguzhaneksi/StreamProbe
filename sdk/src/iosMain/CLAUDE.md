# iosMain — AVFoundation / SKIE invariants

iOS-specific invariants for the StreamProbe SDK. The repo-root `CLAUDE.md` has build commands, the common core, and cross-cutting invariants; this file is pulled in on demand when you edit `iosMain` sources.

- **`AVURLAsset` required for `variants`:** `variants` is an extension on `AVURLAsset`, not `AVAsset`. Always cast `item.asset as? AVURLAsset`.
- **Observers scoped to `currentItem`:** prevents sibling-player / `AVQueuePlayer` callbacks from firing this probe's handlers (item-change tracking not yet supported).
- **Access-log entries processed one notification behind:** the probe reads `accessLog()?.events?.dropLast(1)` so only finalized (non-mutating) entries are read; the last is picked up by the next notification.
- **Monotonic timestamps:** `nowMs()` uses `CACurrentMediaTime()` + an epoch offset computed once at `attach()`, avoiding wall-clock non-monotonicity.
- **SKIE bridge:** `co.touchlab.skie` (SKIE) bridges `StateFlow` → Swift `AsyncSequence` and sealed interfaces (`OverlayRow`/`TrackSwitchEvent`/`DrmSessionEvent`/`ViewMode`) → exhaustive Swift enums via `onEnum(of:)`. SKIE enum cases are lowerCamelCase (`ViewMode.tracks`, `CacheStatus.hit`, …); the `StreamProbe` class is exposed to Swift as `StreamProbe_` (name-mangling). Generated wrappers live in `sdk/build/skie/`.
- **Simulator TLS limitation (not a code defect):** the sandboxed simulator can't complete TLS handshakes (process-isolated network daemon), so live HTTPS `AVPlayer` legs fail with `status=2`. The pure mapper tests `AVMetricMappersTest`/`AVAccessLogMappersTest` give hermetic coverage of the mapping logic. The Swift `AVPlayerProbe`'s live behavior must be verified on a real device or a simulator outside the sandbox.
