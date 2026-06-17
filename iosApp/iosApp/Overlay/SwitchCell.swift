import UIKit
import StreamProbe

/// Switches tab row.
/// Row 1: #N · type badge (VID/AUD/SUB) · switch text.
/// Row 2 (indent 32pt): buffer · reason · relative timestamp.
final class SwitchCell: UITableViewCell {
    static let reuseID = "SwitchCell"

    private let indexLabel = UILabel()
    private let typeLabel = UILabel()
    private let switchLabel = UILabel()
    private let bufferLabel = UILabel()
    private let reasonLabel = UILabel()
    private let timestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        typeLabel.font = .systemFont(ofSize: 10, weight: .bold)
        typeLabel.translatesAutoresizingMaskIntoConstraints = false

        switchLabel.font = .systemFont(ofSize: 11)
        switchLabel.textColor = OverlayTheme.white100
        switchLabel.numberOfLines = 0
        switchLabel.translatesAutoresizingMaskIntoConstraints = false

        for l in [bufferLabel, reasonLabel] {
            l.font = .systemFont(ofSize: 10)
            l.textColor = OverlayTheme.white60
            l.translatesAutoresizingMaskIntoConstraints = false
        }
        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        [indexLabel, typeLabel, switchLabel, bufferLabel, reasonLabel, timestampLabel]
            .forEach { contentView.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            indexLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            typeLabel.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            typeLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),
            typeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 22),

            switchLabel.leadingAnchor.constraint(equalTo: typeLabel.trailingAnchor, constant: 4),
            switchLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            switchLabel.centerYAnchor.constraint(equalTo: indexLabel.centerYAnchor),

            bufferLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 32),
            bufferLabel.topAnchor.constraint(equalTo: indexLabel.bottomAnchor, constant: 2),
            bufferLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),

            reasonLabel.leadingAnchor.constraint(equalTo: bufferLabel.trailingAnchor, constant: 6),
            reasonLabel.centerYAnchor.constraint(equalTo: bufferLabel.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: reasonLabel.trailingAnchor, constant: 6),
            timestampLabel.centerYAnchor.constraint(equalTo: bufferLabel.centerYAnchor),
            timestampLabel.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -10),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: any TrackSwitchEvent, baseTimestampMs: Int64) {
        indexLabel.text = "#\(index + 1)"
        bufferLabel.text = OverlayFormattersSwift.formatBufferDuration(event.bufferDurationMs)
        reasonLabel.text = OverlayFormattersSwift.formatSwitchReason(event.reason)
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)

        switch onEnum(of: event) {
        case .videoSwitch(let v):
            typeLabel.text = "VID"
            typeLabel.textColor = OverlayTheme.vidBlue
            switchLabel.text = OverlayFormattersSwift.formatAbrSwitch(from: v.previousTrack, to: v.newTrack)
        case .audioSwitch(let a):
            typeLabel.text = "AUD"
            typeLabel.textColor = OverlayTheme.audGreen
            let prev = a.previousTrack.map { $0.label ?? $0.language ?? "?" }
            let next = a.newTrack.label ?? a.newTrack.language ?? "?"
            switchLabel.text = prev != nil ? "\(prev!) → \(next)" : "— → \(next)"
        case .subtitleSwitch(let s):
            typeLabel.text = "SUB"
            typeLabel.textColor = OverlayTheme.subPurple
            let prev = s.previousTrack.map { $0.label ?? $0.language ?? "?" }
            let next = s.newTrack.map { $0.label ?? $0.language ?? "?" } ?? "Off"
            switchLabel.text = prev != nil ? "\(prev!) → \(next)" : "— → \(next)"
        }
    }
}
