import UIKit
import StreamProbeCore

/// Stats section — vertical stack of (section label → value) pairs, matching Android.
/// DRM label+value are hidden unless `drmVisible`.
final class StatsView: UIView {

    private let stack = UIStackView()

    private let activeTrackLabel = StatsView.valueLabel(size: 14)
    private let audioLabel       = StatsView.valueLabel(size: 12)
    private let subtitleLabel    = StatsView.valueLabel(size: 12)
    private let drmSectionLabel: UILabel
    private let drmValueLabel    = StatsView.valueLabel(size: 12)
    private let segmentLabel     = StatsView.valueLabel(size: 12)
    private let cdnLabel         = StatsView.valueLabel(size: 12)

    private let activeTrackHeader = StatsView.sectionLabel("ACTIVE TRACK")
    private let audioHeader       = StatsView.sectionLabel("AUDIO")
    private let subtitleHeader    = StatsView.sectionLabel("SUBTITLE")
    private let segmentHeader     = StatsView.sectionLabel("LATEST SEGMENT")
    private let cdnHeader         = StatsView.sectionLabel("CDN STATUS")

    override init(frame: CGRect) {
        drmSectionLabel = StatsView.sectionLabel("DRM")
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false

        let rows: [(UILabel, UILabel, CGFloat)] = [
            (activeTrackHeader, activeTrackLabel, 8),
            (audioHeader,       audioLabel,       8),
            (subtitleHeader,    subtitleLabel,    12),
            (drmSectionLabel,   drmValueLabel,    12),
            (segmentHeader,     segmentLabel,     8),
            (cdnHeader,         cdnLabel,         12),
        ]
        for (header, value, gapAfter) in rows {
            stack.addArrangedSubview(header)
            stack.setCustomSpacing(4, after: header)
            stack.addArrangedSubview(value)
            stack.setCustomSpacing(gapAfter, after: value)
        }

        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func render(_ stats: OverlayStatsState) {
        activeTrackLabel.text = stats.activeTrackText
        audioLabel.text       = stats.activeAudioText
        subtitleLabel.text    = stats.activeSubtitleText
        segmentLabel.text     = stats.latestSegmentText
        cdnLabel.text         = stats.cdnStatusText
        drmValueLabel.text    = stats.drmStatusText
        drmSectionLabel.isHidden = !stats.drmVisible
        drmValueLabel.isHidden   = !stats.drmVisible
    }

    // ── Factories ─────────────────────────────────────────────────
    private static func sectionLabel(_ text: String) -> UILabel {
        let l = UILabel()
        l.attributedText = NSAttributedString(
            string: text,
            attributes: [
                .font: UIFont.systemFont(ofSize: 10, weight: .bold),
                .foregroundColor: OverlayTheme.white50,
                .kern: 1.0,
            ])
        l.numberOfLines = 1
        return l
    }

    private static func valueLabel(size: CGFloat) -> UILabel {
        let l = UILabel()
        l.font = .systemFont(ofSize: size, weight: .medium)
        l.textColor = OverlayTheme.white100
        l.numberOfLines = 0
        return l
    }
}
