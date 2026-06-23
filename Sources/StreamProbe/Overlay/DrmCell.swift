import UIKit
import StreamProbeCore

/// DRM tab row: #N · dot · scheme badge · event label · latency (KeysLoaded only) · timestamp.
final class DrmCell: UITableViewCell {
    static let reuseID = "DrmCell"

    private let indexLabel = UILabel()
    private let dot = UIView()
    private let schemeLabel = UILabel()
    private let eventLabel = UILabel()
    private let latencyLabel = UILabel()
    private let timestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        dot.layer.cornerRadius = 4
        dot.translatesAutoresizingMaskIntoConstraints = false

        schemeLabel.font = .systemFont(ofSize: 10, weight: .bold)
        schemeLabel.textColor = OverlayTheme.white80
        schemeLabel.translatesAutoresizingMaskIntoConstraints = false

        eventLabel.font = .systemFont(ofSize: 11)
        eventLabel.textColor = OverlayTheme.white100
        eventLabel.numberOfLines = 0
        eventLabel.translatesAutoresizingMaskIntoConstraints = false

        latencyLabel.font = .systemFont(ofSize: 10)
        latencyLabel.textColor = OverlayTheme.white60
        latencyLabel.translatesAutoresizingMaskIntoConstraints = false

        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        [indexLabel, dot, schemeLabel, eventLabel, latencyLabel, timestampLabel]
            .forEach { contentView.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            dot.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            dot.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),

            schemeLabel.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 4),
            schemeLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            schemeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 24),

            eventLabel.leadingAnchor.constraint(equalTo: schemeLabel.trailingAnchor, constant: 4),
            eventLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            latencyLabel.leadingAnchor.constraint(equalTo: eventLabel.trailingAnchor, constant: 4),
            latencyLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: latencyLabel.trailingAnchor, constant: 6),
            timestampLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            timestampLabel.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -10),
        ])
        eventLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: any DrmSessionEvent, baseTimestampMs: Int64) {
        indexLabel.text = "#\(index + 1)"
        dot.backgroundColor = OverlayTheme.drmEventDot(event)
        schemeLabel.text = OverlayFormattersSwift.formatDrmSchemeBadge(event.scheme)
        eventLabel.text = OverlayFormattersSwift.formatDrmEventLabel(event)
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)

        if case .keysLoaded(let e) = onEnum(of: event) {
            latencyLabel.text = "\(e.licenseLatencyMs)ms"
            latencyLabel.isHidden = false
        } else {
            latencyLabel.isHidden = true
        }
    }
}
