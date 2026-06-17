import UIKit
import StreamProbe

/// Errors tab row. Summary line is always visible; the detail block shows when expanded.
/// Expansion state is owned by the data source (the cell only renders it).
final class ErrorCell: UITableViewCell {
    static let reuseID = "ErrorCell"

    private let indexLabel = UILabel()
    private let dot = UIView()
    private let categoryLabel = UILabel()
    private let messageLabel = UILabel()
    private let timestampLabel = UILabel()
    private let chevronLabel = UILabel()

    private let detailStack = UIStackView()
    private let fullMessageLabel = UILabel()
    private let detailLabel = UILabel()
    private let absoluteTimestampLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        indexLabel.font = .systemFont(ofSize: 11, weight: .medium)
        indexLabel.textColor = OverlayTheme.white60
        indexLabel.translatesAutoresizingMaskIntoConstraints = false

        dot.layer.cornerRadius = 4
        dot.translatesAutoresizingMaskIntoConstraints = false

        categoryLabel.font = .systemFont(ofSize: 10, weight: .bold)
        categoryLabel.textColor = OverlayTheme.white80
        categoryLabel.translatesAutoresizingMaskIntoConstraints = false

        messageLabel.font = .systemFont(ofSize: 11)
        messageLabel.textColor = OverlayTheme.white100
        messageLabel.lineBreakMode = .byTruncatingTail
        messageLabel.numberOfLines = 1
        messageLabel.translatesAutoresizingMaskIntoConstraints = false

        timestampLabel.font = .systemFont(ofSize: 10)
        timestampLabel.textColor = OverlayTheme.white40
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        chevronLabel.font = .systemFont(ofSize: 12)
        chevronLabel.textColor = OverlayTheme.white40
        chevronLabel.textAlignment = .center
        chevronLabel.translatesAutoresizingMaskIntoConstraints = false

        let summary = UIView()
        summary.translatesAutoresizingMaskIntoConstraints = false
        [indexLabel, dot, categoryLabel, messageLabel, timestampLabel, chevronLabel]
            .forEach { summary.addSubview($0) }

        NSLayoutConstraint.activate([
            indexLabel.leadingAnchor.constraint(equalTo: summary.leadingAnchor, constant: 10),
            indexLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            indexLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 28),

            dot.leadingAnchor.constraint(equalTo: indexLabel.trailingAnchor, constant: 4),
            dot.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),

            categoryLabel.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 4),
            categoryLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            messageLabel.leadingAnchor.constraint(equalTo: categoryLabel.trailingAnchor, constant: 4),
            messageLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            timestampLabel.leadingAnchor.constraint(equalTo: messageLabel.trailingAnchor, constant: 4),
            timestampLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),

            chevronLabel.leadingAnchor.constraint(equalTo: timestampLabel.trailingAnchor, constant: 2),
            chevronLabel.trailingAnchor.constraint(equalTo: summary.trailingAnchor, constant: -10),
            chevronLabel.centerYAnchor.constraint(equalTo: summary.centerYAnchor),
            chevronLabel.widthAnchor.constraint(equalToConstant: 20),

            summary.heightAnchor.constraint(greaterThanOrEqualToConstant: 30),
        ])
        messageLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        messageLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        // Detail block
        for l in [fullMessageLabel, detailLabel, absoluteTimestampLabel] {
            l.numberOfLines = 0
            l.translatesAutoresizingMaskIntoConstraints = false
        }
        fullMessageLabel.font = .systemFont(ofSize: 11)
        fullMessageLabel.textColor = OverlayTheme.white80
        detailLabel.font = .systemFont(ofSize: 10)
        detailLabel.textColor = OverlayTheme.white60
        absoluteTimestampLabel.font = .systemFont(ofSize: 10)
        absoluteTimestampLabel.textColor = OverlayTheme.white40

        detailStack.axis = .vertical
        detailStack.spacing = 2
        detailStack.isLayoutMarginsRelativeArrangement = true
        detailStack.layoutMargins = UIEdgeInsets(top: 0, left: 10, bottom: 6, right: 10)
        detailStack.translatesAutoresizingMaskIntoConstraints = false
        [fullMessageLabel, detailLabel, absoluteTimestampLabel].forEach { detailStack.addArrangedSubview($0) }

        let outer = UIStackView(arrangedSubviews: [summary, detailStack])
        outer.axis = .vertical
        outer.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(outer)
        NSLayoutConstraint.activate([
            outer.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            outer.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5),
            outer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            outer.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            summary.leadingAnchor.constraint(equalTo: outer.leadingAnchor),
            summary.trailingAnchor.constraint(equalTo: outer.trailingAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(index: Int, event: PlaybackErrorEvent, baseTimestampMs: Int64, expanded: Bool) {
        indexLabel.text = "#\(index + 1)"
        dot.backgroundColor = OverlayTheme.errorCategoryDot(event.category)
        categoryLabel.text = OverlayFormattersSwift.formatErrorCategory(event.category)
        messageLabel.text = event.message
        timestampLabel.text = OverlayFormattersSwift.formatRelativeTimestamp(event.timestampMs, base: baseTimestampMs)
        chevronLabel.text = expanded ? "▴" : "▾"
        detailStack.isHidden = !expanded
        if expanded {
            fullMessageLabel.text = event.message
            detailLabel.text = event.detail ?? ""
            detailLabel.isHidden = (event.detail ?? "").isEmpty
            absoluteTimestampLabel.text = OverlayFormattersSwift.formatAbsoluteTimestamp(event.timestampMs)
        }
    }
}
