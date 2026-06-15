import UIKit
import StreamProbe

final class StatsView: UIView {
    private let stack: UIStackView = {
        let sv = UIStackView()
        sv.axis = .vertical
        sv.spacing = 2
        sv.translatesAutoresizingMaskIntoConstraints = false
        return sv
    }()

    private let videoLabel   = StatsView.makeLabel()
    private let audioLabel   = StatsView.makeLabel()
    private let subtitleLabel = StatsView.makeLabel()
    private let segmentLabel = StatsView.makeLabel()
    private let cdnLabel     = StatsView.makeLabel()
    private let drmLabel     = StatsView.makeLabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        [videoLabel, audioLabel, subtitleLabel, segmentLabel, cdnLabel, drmLabel]
            .forEach { stack.addArrangedSubview($0) }
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func render(_ stats: OverlayStatsState) {
        videoLabel.text    = "▶ \(stats.activeTrackText)"
        audioLabel.text    = "♫ \(stats.activeAudioText)"
        subtitleLabel.text = "CC \(stats.activeSubtitleText)"
        segmentLabel.text  = "⬇ \(stats.latestSegmentText)"
        cdnLabel.text      = "CDN \(stats.cdnStatusText)"
        drmLabel.isHidden  = !stats.drmVisible
        drmLabel.text      = "DRM \(stats.drmStatusText)"
    }

    private static func makeLabel() -> UILabel {
        let lbl = UILabel()
        lbl.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        lbl.textColor = .white
        lbl.numberOfLines = 1
        lbl.adjustsFontSizeToFitWidth = true
        lbl.minimumScaleFactor = 0.7
        return lbl
    }
}
