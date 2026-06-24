import Foundation
import StreamProbeCore

/// Swift port of the internal Kotlin `OverlayFormatters` + `DrmFormatters`
/// (those are `internal` in commonMain and not visible to Swift).
enum OverlayFormattersSwift {

    // ── Numbers ───────────────────────────────────────────────────
    private static func oneDecimal(_ v: Double) -> String {
        String(format: "%.1f", v)
    }

    static func formatBytes(_ value: Int64, suffix: String = "") -> String {
        if value >= 1_000_000 { return "\(oneDecimal(Double(value) / 1_000_000)) MB\(suffix)" }
        if value >= 1_000     { return "\(oneDecimal(Double(value) / 1_000)) KB\(suffix)" }
        return "\(value) B\(suffix)"
    }

    static func formatThroughput(_ bytesPerSec: Int64) -> String {
        formatBytes(bytesPerSec, suffix: "/s")
    }

    static func formatBitrate(_ bps: Int32) -> String {
        if bps >= 1_000_000 { return "\(oneDecimal(Double(bps) / 1_000_000)) Mbps" }
        if bps >= 1_000     { return "\(bps / 1_000) kbps" }
        if bps > 0          { return "\(bps) bps" }
        return "? bps"
    }

    static func formatResolution(_ width: Int32, _ height: Int32) -> String {
        (width > 0 && height > 0) ? "\(width)×\(height)" : "Audio only"
    }

    // ── Segment ───────────────────────────────────────────────────
    static func formatSegmentDetails(_ metric: SegmentMetric) -> String {
        var parts = ["Size: \(formatBytes(metric.sizeBytes))",
                     "TP: \(formatThroughput(metric.throughputBytesPerSec))"]
        if let t = metric.networkTiming {
            parts.append("TTFB: \(formatTtfb(t))")
        }
        return parts.joined(separator: "  ·  ")
    }

    static func formatTtfb(_ timing: NetworkTiming?) -> String {
        guard let t = timing else { return "—" }
        return "\(t.isEstimated ? "~" : "")\(t.ttfbMs)ms"
    }

    // ── Switches ──────────────────────────────────────────────────
    /// "720p → 1080p", or "1.5 Mbps → 5.0 Mbps" when heights match.
    static func formatAbrSwitch(from: ActiveTrackInfo?, to: ActiveTrackInfo) -> String {
        let toLabel = to.height > 0 ? "\(to.height)p" : formatBitrate(to.bitrate)
        guard let from = from else { return "— → \(toLabel)" }
        let fromLabel = from.height > 0 ? "\(from.height)p" : formatBitrate(from.bitrate)
        if fromLabel == toLabel {
            return "\(formatBitrate(from.bitrate)) → \(formatBitrate(to.bitrate))"
        }
        return "\(fromLabel) → \(toLabel)"
    }

    static func formatBufferDuration(_ bufferMs: Int64) -> String {
        "buf: \(oneDecimal(Double(bufferMs) / 1000))s"
    }

    static func formatSwitchReason(_ reason: SwitchReason) -> String {
        switch reason {
        case .initial:   return "INITIAL"
        case .adaptive:  return "ADAPTIVE"
        case .manual:    return "MANUAL"
        case .trickplay: return "TRICKPLAY"
        case .unknown:   return "UNKNOWN"
        }
    }

    static func formatRelativeTimestamp(_ timestampMs: Int64, base baseMs: Int64) -> String {
        let diff = max(timestampMs - baseMs, 0)
        let totalSec = diff / 1000
        let minutes = totalSec / 60
        let seconds = totalSec % 60
        return "+\(minutes):" + String(format: "%02d", seconds)
    }

    // ── Errors ────────────────────────────────────────────────────
    static func formatErrorCategory(_ category: ErrorCategory) -> String {
        switch category {
        case .loadError:       return "LOAD"
        case .videoCodecError: return "CODEC"
        case .droppedFrames:   return "FRAMES"
        case .audioSinkError:  return "AUDIO"
        case .audioCodecError: return "ACODEC"
        case .drmError:        return "DRM"
        }
    }

    private static let absoluteTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    static func formatAbsoluteTimestamp(_ timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestampMs) / 1000)
        return absoluteTimeFormatter.string(from: date)
    }

    static func formatErrorsForExport(_ errors: [PlaybackErrorEvent], baseTimestampMs: Int64) -> String {
        let header = "[StreamProbe] \(errors.count) errors"
        let rows = errors.enumerated().map { (i, e) -> String in
            let rel = formatRelativeTimestamp(e.timestampMs, base: baseTimestampMs)
            let cat = formatErrorCategory(e.category)
            let abs = formatAbsoluteTimestamp(e.timestampMs)
            var line = "#\(i + 1) \(rel) \(cat) \(e.message) [\(abs)]"
            if let d = e.detail, !d.isEmpty { line += "\n    \(d)" }
            return line
        }
        return ([header] + rows).joined(separator: "\n")
    }

    // ── DRM ───────────────────────────────────────────────────────
    static func formatDrmSchemeBadge(_ scheme: DrmScheme) -> String {
        switch scheme {
        case .widevine:  return "WV"
        case .playready: return "PR"
        case .clearkey:  return "CK"
        case .fairplay:  return "FP"
        case .unknown:   return "DRM"
        }
    }

    static func formatDrmSessionState(_ state: DrmSessionState) -> String {
        switch state {
        case .opening:        return "Opening"
        case .opened:         return "Opened"
        case .openedWithKeys: return "Keys Loaded"
        case .released:       return "Released"
        case .error:          return "Error"
        case .unknown:        return "Unknown"
        }
    }

    static func formatDrmEventLabel(_ event: any DrmSessionEvent) -> String {
        switch onEnum(of: event) {
        case .sessionAcquired(let e): return "Session Acquired (\(formatDrmSessionState(e.state)))"
        case .keysLoaded:             return "Keys Loaded"
        case .sessionReleased:        return "Session Released"
        case .sessionError(let e):    return "Error: \(e.message)"
        }
    }

    // ── Rendition helpers ─────────────────────────────────────────
    // Mirrors the SDK's iOS `displayLanguage` actual, which returns the raw BCP-47 tag
    // (localized language names are a deferred SDK task). Keeps the Tracks cells consistent
    // with the AUDIO/SUBTITLE stat line, which uses that same SDK function.
    static func resolveDisplayName(_ languageTag: String) -> String? {
        languageTag.isEmpty ? nil : languageTag
    }

    static func channelLabel(_ channelCount: Int32) -> String? {
        switch channelCount {
        case 1: return "mono"
        case 2: return "stereo"
        case 6: return "5.1"
        case 8: return "7.1"
        default: return channelCount > 0 ? "\(channelCount)ch" : nil
        }
    }

    static func subtitleMimeShort(_ mimeType: String?) -> String? {
        switch mimeType {
        case "text/vtt", "application/x-media3-webvtt": return "WebVTT"
        case "application/ttml+xml": return "TTML"
        case "application/x-subrip": return "SRT"
        case "text/x-ssa": return "SSA"
        default: return mimeType.flatMap { $0.components(separatedBy: "/").last }
        }
    }
}
