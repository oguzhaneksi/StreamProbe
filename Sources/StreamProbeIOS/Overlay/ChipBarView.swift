import UIKit
import StreamProbeCore

/// Filter chip row. Checked = accent fill + white text; unchecked = clear fill,
/// 1pt accent border, accent text. Title-case labels. DRM chip hidden unless visible.
final class ChipBarView: UIView {

    var onChipSelected: ((ViewMode) -> Void)?

    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private var chips: [ViewMode: UIButton] = [:]
    private var selectedMode: ViewMode = .tracks

    /// Chips shown in normal mode (ERRORS is reached via the error indicator, not a chip).
    private let modes: [ViewMode] = [.tracks, .segments, .switches, .drm]

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false

        // Horizontal scroll so chips keep their natural width and never wrap on narrow panels.
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scrollView)

        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            stack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),
        ])

        for mode in modes {
            let chip = makeChip(mode)
            chips[mode] = chip
            stack.addArrangedSubview(chip)
        }
        updateStyles()
    }

    required init?(coder: NSCoder) { fatalError() }

    func setSelected(_ mode: ViewMode) {
        selectedMode = mode
        updateStyles()
    }

    func setDrmVisible(_ visible: Bool) {
        chips[.drm]?.isHidden = !visible
    }

    private func makeChip(_ mode: ViewMode) -> UIButton {
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 10, bottom: 4, trailing: 10)
        config.title = title(mode)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 11, weight: .medium)
        btn.titleLabel?.numberOfLines = 1
        btn.titleLabel?.lineBreakMode = .byClipping
        btn.setContentCompressionResistancePriority(.required, for: .horizontal)
        btn.layer.cornerRadius = 12
        btn.layer.borderWidth = 1
        btn.clipsToBounds = true
        btn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.selectedMode = mode
            self.updateStyles()
            self.onChipSelected?(mode)
        }, for: .touchUpInside)
        return btn
    }

    private func updateStyles() {
        for (mode, chip) in chips {
            let checked = (mode == selectedMode)
            var config = chip.configuration ?? .plain()
            config.background.backgroundColor = checked ? OverlayTheme.accent : .clear
            config.baseForegroundColor = checked ? OverlayTheme.white100 : OverlayTheme.accent
            chip.configuration = config
            chip.layer.borderColor = (checked ? UIColor.clear : OverlayTheme.accent).cgColor
        }
    }

    private func title(_ mode: ViewMode) -> String {
        switch mode {
        case .tracks:   return "Tracks"
        case .segments: return "Segments"
        case .switches: return "Switches"
        case .drm:      return "DRM"
        case .errors:   return "Errors"
        }
    }
}
