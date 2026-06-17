import UIKit
import StreamProbe

/// Segments tab row.
/// Row 1: #N · "DL: Xms" · cache dot.  Row 2 (indented 32pt): size · throughput · TTFB.
final class SegmentCell: UITableViewCell {
    static let reuseID = "SegmentCell"

    private let indexLabel = UILabel()
    private let durationLabel = UILabel()
    private let cacheDot = UIView()
    private let secondaryLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        durationLabel.font = .systemFont(ofSize: 12, weight: .medium)
        durationLabel.textColor = OverlayTheme.white100
        durationLabel.translatesAutoresizingMaskIntoConstraints = false

        cacheDot.layer.cornerRadius = 4
        cacheDot.translatesAutoresizingMaskIntoConstraints = false

        secondaryLabel.font = .systemFont(ofSize: 10)
        secondaryLabel.textColor = OverlayTheme.white60
        secondaryLabel.numberOfLines = 0
        secondaryLabel.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(indexLabel)
        contentView.addSubview(durationLabel)
        contentView.addSubview(cacheDot)
        contentView.addSubview(secondaryLabel)

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            durationLabel.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            durationLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            cacheDot.widthAnchor.constraint(equalToConstant: 8),
            cacheDot.heightAnchor.constraint(equalToConstant: 8),
            cacheDot.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            cacheDot.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            cacheDot.leadingAnchor.constraint(greaterThanOrEqualTo: durationLabel.trailingAnchor, constant: 6),

            secondaryLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            secondaryLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            secondaryLabel.topAnchor.constraint(equalTo: indexLabel.bottomAnchor, constant: 2),
            secondaryLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, metric: SegmentMetric) {
        indexLabel.text = "#\(index + 1)"
        durationLabel.text = "DL: \(metric.totalDurationMs)ms"
        cacheDot.backgroundColor = OverlayTheme.cacheDot(metric.cdnInfo.cacheStatus)
        secondaryLabel.text = OverlayFormattersSwift.formatSegmentDetails(metric)
    }
}
