import Foundation

/// A selectable HLS stream in the demo. HLS-only by design (Phase 5 scope):
/// no DASH/MP4/DRM entries. Supports both VOD and live streams. `id` is stable per process so SwiftUI `List` identity holds.
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

/// Curated public HLS test streams (no DRM). Mirrors the HLS entries from the Android demo
/// plus a couple of widely-used public HLS test assets.
let demoStreams: [Stream] = [
    Stream(
        title: "Apple BipBop — Advanced (TS)",
        urlString: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
    ),
    Stream(
        title: "Apple BipBop — Advanced (fMP4)",
        urlString: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
    ),
    Stream(
        title: "Apple HEVC — Multi-Subtitle",
        urlString: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8"
    ),
    Stream(
        title: "Mux — Big Buck Bunny",
        urlString: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    ),
    Stream(
        title: "Mux — Tears of Steel",
        urlString: "https://test-streams.mux.dev/test_001/stream.m3u8"
    ),
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
]
