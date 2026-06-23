import UIKit

/// Errors-mode header that replaces the chip row: [← Back] [Errors (N)] [Clear] [↗].
final class ErrorsHeaderView: UIView {

    let backButton  = UIButton(type: .system)
    let titleLabel  = UILabel()
    let clearButton = UIButton(type: .system)
    let shareButton = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        styleTextButton(backButton, title: "← Back", size: 12)
        styleTextButton(clearButton, title: "Clear", size: 12)
        styleTextButton(shareButton, title: "↗", size: 14)
        backButton.accessibilityLabel = "Back to previous view"
        clearButton.accessibilityLabel = "Clear errors"
        shareButton.accessibilityLabel = "Share errors"

        titleLabel.font = .systemFont(ofSize: 12, weight: .bold)
        titleLabel.textColor = OverlayTheme.white100
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        let stack = UIStackView(arrangedSubviews: [backButton, titleLabel, clearButton, shareButton])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        backButton.setContentHuggingPriority(.required, for: .horizontal)
        clearButton.setContentHuggingPriority(.required, for: .horizontal)
        shareButton.setContentHuggingPriority(.required, for: .horizontal)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 32),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func setTitle(_ text: String) { titleLabel.text = text }

    private func styleTextButton(_ btn: UIButton, title: String, size: CGFloat) {
        btn.setTitle(title, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: size)
        btn.setTitleColor(OverlayTheme.accent, for: .normal)
        btn.tintColor = OverlayTheme.accent
        btn.translatesAutoresizingMaskIntoConstraints = false
    }
}
