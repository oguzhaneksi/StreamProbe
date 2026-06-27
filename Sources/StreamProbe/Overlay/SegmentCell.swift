import UIKit
import StreamProbeCore

/// Segments tab row.
/// Row 1: #N · [badge] · ext · "DL: Xms" · cache dot.  Row 2 (indented 32pt): size · throughput · TTFB.
final class SegmentCell: UITableViewCell {
    static let reuseID = "SegmentCell"

    private let indexLabel = UILabel()
    private let trackBadgeLabel = UILabel()
    private let extensionLabel = UILabel()
    private let durationLabel = UILabel()
    private let cacheDot = UIView()
    private let secondaryLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.setContentHuggingPriority(.required, for: .horizontal)

        trackBadgeLabel.font = .systemFont(ofSize: 9, weight: .bold)
        trackBadgeLabel.textColor = .white
        trackBadgeLabel.textAlignment = .center
        trackBadgeLabel.layer.cornerRadius = 4
        trackBadgeLabel.layer.masksToBounds = true
        trackBadgeLabel.translatesAutoresizingMaskIntoConstraints = false
        trackBadgeLabel.widthAnchor.constraint(equalToConstant: 16).isActive = true
        trackBadgeLabel.heightAnchor.constraint(equalToConstant: 14).isActive = true

        extensionLabel.font = .systemFont(ofSize: 9)
        extensionLabel.textColor = OverlayTheme.white70
        extensionLabel.setContentHuggingPriority(.required, for: .horizontal)

        durationLabel.font = .systemFont(ofSize: 12, weight: .medium)
        durationLabel.textColor = OverlayTheme.white100

        let row1 = UIStackView(arrangedSubviews: [indexLabel, trackBadgeLabel, extensionLabel, durationLabel])
        row1.axis = .horizontal
        row1.alignment = .center
        row1.spacing = 4
        row1.translatesAutoresizingMaskIntoConstraints = false

        cacheDot.layer.cornerRadius = 4
        cacheDot.translatesAutoresizingMaskIntoConstraints = false

        secondaryLabel.font = .systemFont(ofSize: 10)
        secondaryLabel.textColor = OverlayTheme.white60
        secondaryLabel.numberOfLines = 0
        secondaryLabel.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(row1)
        contentView.addSubview(cacheDot)
        contentView.addSubview(secondaryLabel)

        NSLayoutConstraint.activate([
            row1.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            row1.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            cacheDot.widthAnchor.constraint(equalToConstant: 8),
            cacheDot.heightAnchor.constraint(equalToConstant: 8),
            cacheDot.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            cacheDot.centerYAnchor.constraint(equalTo: row1.centerYAnchor),
            cacheDot.leadingAnchor.constraint(greaterThanOrEqualTo: row1.trailingAnchor, constant: 6),

            secondaryLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            secondaryLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            secondaryLabel.topAnchor.constraint(equalTo: row1.bottomAnchor, constant: 2),
            secondaryLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, metric: SegmentMetric) {
        indexLabel.text = "#\(index + 1)"

        if let badge = OverlayFormattersSwift.segmentTrackBadge(metric.trackType) {
            trackBadgeLabel.text = badge
            trackBadgeLabel.backgroundColor = OverlayTheme.trackBadge(metric.trackType)
            trackBadgeLabel.isHidden = false
        } else {
            trackBadgeLabel.isHidden = true
        }

        if let ext = OverlayFormattersSwift.segmentExtension(metric.uri) {
            extensionLabel.text = ext
            extensionLabel.isHidden = false
        } else {
            extensionLabel.isHidden = true
        }

        durationLabel.text = "DL: \(metric.totalDurationMs)ms"
        cacheDot.backgroundColor = OverlayTheme.cacheDot(metric.cdnInfo.cacheStatus)
        secondaryLabel.text = OverlayFormattersSwift.formatSegmentDetails(metric)
    }
}
