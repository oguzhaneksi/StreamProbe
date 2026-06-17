import UIKit
import StreamProbe

/// 44pt header bar: title (flex) · error pill · collapse button.
/// Background is headerBg with only the top corners rounded (14pt).
final class HeaderView: UIView {

    let titleLabel = UILabel()
    let errorIndicator = UIButton(type: .system)
    let collapseButton = UIButton(type: .system)

    private let bgLayer = CALayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        bgLayer.backgroundColor = OverlayTheme.headerBg.cgColor
        bgLayer.cornerRadius = OverlayTheme.panelCorner
        bgLayer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        layer.addSublayer(bgLayer)

        // Title — 13pt bold, white, kern 0.52 (0.04em × 13).
        titleLabel.attributedText = NSAttributedString(
            string: "StreamProbe",
            attributes: [
                .font: UIFont.systemFont(ofSize: 13, weight: .bold),
                .foregroundColor: OverlayTheme.white100,
                .kern: 0.52,
            ])
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        // Error pill — red bg, 10pt corner, semibold 11pt white, hidden by default.
        errorIndicator.backgroundColor = OverlayTheme.errorRed
        errorIndicator.layer.cornerRadius = 10
        errorIndicator.titleLabel?.font = .systemFont(ofSize: 11, weight: .bold)
        errorIndicator.setTitleColor(OverlayTheme.white100, for: .normal)
        errorIndicator.tintColor = OverlayTheme.white100
        errorIndicator.contentEdgeInsets = UIEdgeInsets(top: 0, left: 6, bottom: 0, right: 6)
        errorIndicator.isHidden = true
        errorIndicator.translatesAutoresizingMaskIntoConstraints = false

        // Collapse — "▾" 18pt, white60, 32×32 tap target.
        collapseButton.setTitle("▾", for: .normal)
        collapseButton.titleLabel?.font = .systemFont(ofSize: 18)
        collapseButton.setTitleColor(OverlayTheme.white60, for: .normal)
        collapseButton.tintColor = OverlayTheme.white60
        collapseButton.translatesAutoresizingMaskIntoConstraints = false

        addSubview(titleLabel)
        addSubview(errorIndicator)
        addSubview(collapseButton)

        NSLayoutConstraint.activate([
            heightAnchor.constraint(equalToConstant: 44),

            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            titleLabel.centerYAnchor.constraint(equalTo: centerYAnchor),

            errorIndicator.trailingAnchor.constraint(equalTo: collapseButton.leadingAnchor, constant: -4),
            errorIndicator.centerYAnchor.constraint(equalTo: centerYAnchor),
            errorIndicator.heightAnchor.constraint(equalToConstant: 20),
            errorIndicator.widthAnchor.constraint(greaterThanOrEqualToConstant: 48),

            collapseButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            collapseButton.centerYAnchor.constraint(equalTo: centerYAnchor),
            collapseButton.widthAnchor.constraint(equalToConstant: 32),
            collapseButton.heightAnchor.constraint(equalToConstant: 32),
        ])
        titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: errorIndicator.leadingAnchor, constant: -4).isActive = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        bgLayer.frame = bounds
    }

    func applyErrorIndicator(_ indicator: ErrorIndicatorState?) {
        if let ind = indicator {
            errorIndicator.setTitle(ind.text, for: .normal)
            errorIndicator.accessibilityLabel = ind.contentDescription
            errorIndicator.isHidden = false
        } else {
            errorIndicator.isHidden = true
        }
    }

    /// Collapsed → arrow points down (identity); expanded → rotated 180°.
    func applyCollapsed(_ isCollapsed: Bool) {
        collapseButton.transform = isCollapsed ? .identity : CGAffineTransform(rotationAngle: .pi)
        collapseButton.accessibilityLabel = isCollapsed ? "Expand" : "Collapse"
    }
}
