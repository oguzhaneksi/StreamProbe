import UIKit
import StreamProbe

final class ChipBarView: UIView {
    var onChipSelected: ((ViewMode) -> Void)?
    private var selectedMode: ViewMode = .tracks

    private let scrollView: UIScrollView = {
        let sv = UIScrollView()
        sv.showsHorizontalScrollIndicator = false
        sv.translatesAutoresizingMaskIntoConstraints = false
        return sv
    }()

    private let stack: UIStackView = {
        let sv = UIStackView()
        sv.axis = .horizontal
        sv.spacing = 6
        sv.translatesAutoresizingMaskIntoConstraints = false
        return sv
    }()

    private var chipButtons: [ViewMode: UIButton] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func setup() {
        addSubview(scrollView)
        scrollView.addSubview(stack)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 4),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -4),
            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -8),
            stack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor, constant: -8),
        ])

        for mode in [ViewMode.tracks, .segments, .switches, .drm, .errors] {
            let btn = makeChip(mode)
            chipButtons[mode] = btn
            stack.addArrangedSubview(btn)
        }
        updateSelection()
    }

    private func makeChip(_ mode: ViewMode) -> UIButton {
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 10, bottom: 4, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.setTitle(label(for: mode), for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 11, weight: .semibold)
        btn.layer.cornerRadius = 12
        btn.layer.borderWidth = 1
        btn.clipsToBounds = true
        btn.addTarget(self, action: #selector(chipTapped(_:)), for: .touchUpInside)
        btn.tag = modeTag(mode)
        return btn
    }

    @objc private func chipTapped(_ sender: UIButton) {
        let mode = viewMode(for: sender.tag)
        selectedMode = mode
        updateSelection()
        onChipSelected?(mode)
    }

    private func updateSelection() {
        for (mode, btn) in chipButtons {
            let selected = mode == selectedMode
            btn.backgroundColor = selected ? .white.withAlphaComponent(0.9) : .clear
            btn.tintColor = selected ? .black : .white
            btn.layer.borderColor = UIColor.white.withAlphaComponent(0.5).cgColor
        }
    }

    func setSelected(mode: ViewMode) {
        selectedMode = mode
        updateSelection()
    }

    private func label(for mode: ViewMode) -> String {
        switch mode {
        case .tracks:   return "TRACKS"
        case .segments: return "SEGMENTS"
        case .switches: return "SWITCHES"
        case .drm:      return "DRM"
        case .errors:   return "ERRORS"
        }
    }

    private func modeTag(_ mode: ViewMode) -> Int {
        switch mode {
        case .tracks:   return 0
        case .segments: return 1
        case .switches: return 2
        case .drm:      return 3
        case .errors:   return 4
        }
    }

    private func viewMode(for tag: Int) -> ViewMode {
        switch tag {
        case 1: return .segments
        case 2: return .switches
        case 3: return .drm
        case 4: return .errors
        default: return .tracks
        }
    }
}
