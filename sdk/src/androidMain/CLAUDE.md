# androidMain — Media3 / ExoPlayer invariants

Android-specific invariants for the StreamProbe SDK. The repo-root `CLAUDE.md` has build commands, the common core, and cross-cutting invariants; this file is pulled in on demand when you edit `androidMain` sources.

- **IMPORTANT — TTFB redirect-key invariant. YOU MUST follow this:** in `PlayerInterceptor.onLoadCompleted`, the `NetworkTimingRegistry` lookup **MUST** use `loadEventInfo.dataSpec.uri` (the pre-redirect URI `TimingDataSource.open` recorded) — **NEVER** `loadEventInfo.uri` (post-redirect). The post-redirect URI silently misses TTFB on every CDN-redirected stream (no error, just wrong data). `SegmentMetric.uri` still stores `loadEventInfo.uri` for display.
- **`TimingDataSourceFactory` must be outermost:** so error-injection/other inner adapters that throw in `open()` propagate *before* the timing record is written — no false TTFB on injected errors.
- **Video switch signal split:** `onVideoInputFormatChanged` (decoder-level) is authoritative for `VideoSwitch`. `onDownstreamFormatChanged` only caches the reason into `pendingVideoSwitchReason`, consumed when the format change fires — avoids duplicate events. A `DEFAULT`-type downstream event updates the pending reason only when `format.width > 0 || format.height > 0` (guards against a muxed-audio `DEFAULT` overwriting the video reason).
- **Subtitle-disabled event:** when `probeTracks` finds `foundSubtitle == null` but `lastSubtitleTrack != null`, it emits `SubtitleSwitch(newTrack = null, MANUAL)`.
- **`isSelected`** is set directly from `Tracks.Group.isTrackSelected(i)` — no secondary active-track comparison in the manager or adapters.
- **DRM tracker isolation:** DRM callbacks live in a separate `DrmSessionTracker` `AnalyticsListener` (keeps `PlayerInterceptor` under `TooManyFunctions` while staying cohesive).
- **DRM scheme from timeline:** `detectScheme` reads `eventTime.timeline`, not `player.currentMediaItem`, to avoid a playlist-transition race.
- **DRM dual surface:** `onDrmSessionManagerError` writes to both `drmSessionEvents` and `playbackErrors` via a single write path (no duplication).
