import UIKit
import StreamProbe

/// Colors and dot-color factories for the debug overlay.
/// Mirrors `OverlayDrawables.kt`. Hex strings use Android ARGB order (#AARRGGBB)
/// when an alpha is present, otherwise #RRGGBB.
enum OverlayTheme {
    // ── Panel chrome ──────────────────────────────────────────────
    static let panelBg      = UIColor(argbHex: "#E6101024")
    static let headerBg     = UIColor(argbHex: "#331A1A3A")
    static let panelCorner: CGFloat = 14

    // ── Accent + status ───────────────────────────────────────────
    static let accent       = UIColor(argbHex: "#66B2FF")
    static let errorRed     = UIColor(argbHex: "#FF453A")
    static let activeGreen  = UIColor(argbHex: "#30D158")
    static let staleOrange  = UIColor(argbHex: "#FF9F0A")
    static let droppedYellow = UIColor(argbHex: "#FFD60A")
    static let bypassPurple = UIColor(argbHex: "#BF5AF2")
    static let drmCyan      = UIColor(argbHex: "#64D2FF")
    static let inactiveDot  = UIColor(argbHex: "#555555")

    // ── Switch type badges ────────────────────────────────────────
    static let vidBlue      = UIColor(argbHex: "#4FC3F7")
    static let audGreen     = UIColor(argbHex: "#A5D6A7")
    static let subPurple    = UIColor(argbHex: "#CE93D8")

    // ── DRM dots ──────────────────────────────────────────────────
    static let drmAcquiredBlue = UIColor(argbHex: "#0A84FF")
    static let drmReleasedGray = UIColor(argbHex: "#8E8E93")

    // ── Text alphas (white) ───────────────────────────────────────
    static let white100 = UIColor.white
    static let white80  = UIColor.white.withAlphaComponent(0.80)
    static let white60  = UIColor.white.withAlphaComponent(0.60)
    static let white50  = UIColor.white.withAlphaComponent(0.50)
    static let white40  = UIColor.white.withAlphaComponent(0.40)

    // ── Dot factories (mirror OverlayDrawables.kt) ────────────────
    static func cacheDot(_ status: CacheStatus) -> UIColor {
        switch status {
        case .hit:     return activeGreen
        case .miss:    return errorRed
        case .stale:   return staleOrange
        case .bypass:  return bypassPurple
        case .unknown: return inactiveDot
        }
    }

    static func errorCategoryDot(_ category: ErrorCategory) -> UIColor {
        switch category {
        case .loadError:       return errorRed
        case .videoCodecError: return staleOrange
        case .droppedFrames:   return droppedYellow
        case .audioSinkError:  return bypassPurple
        case .audioCodecError: return activeGreen
        case .drmError:        return drmCyan
        }
    }

    static func drmEventDot(_ event: any DrmSessionEvent) -> UIColor {
        switch onEnum(of: event) {
        case .sessionAcquired: return drmAcquiredBlue
        case .keysLoaded:      return activeGreen
        case .sessionReleased: return drmReleasedGray
        case .sessionError:    return drmCyan
        }
    }
}

extension UIColor {
    /// Parses "#RRGGBB" or "#AARRGGBB" (Android ARGB order).
    convenience init(argbHex: String) {
        let hex = argbHex.hasPrefix("#") ? String(argbHex.dropFirst()) : argbHex
        var value: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&value)
        let a, r, g, b: UInt64
        if hex.count == 8 {
            a = (value >> 24) & 0xFF
            r = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            b = value & 0xFF
        } else {
            a = 0xFF
            r = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            b = value & 0xFF
        }
        self.init(red: CGFloat(r) / 255, green: CGFloat(g) / 255,
                  blue: CGFloat(b) / 255, alpha: CGFloat(a) / 255)
    }
}
