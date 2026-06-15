import UIKit
import StreamProbe

final class OverlayPanelView: UIView {
    let chipBar = ChipBarView()
    let statsView = StatsView()
    lazy var tableView: UITableView = {
        let tv = UITableView()
        tv.backgroundColor = .clear
        tv.separatorColor = UIColor.white.withAlphaComponent(0.15)
        tv.translatesAutoresizingMaskIntoConstraints = false
        tv.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        return tv
    }()

    private let headerLabel: UILabel = {
        let lbl = UILabel()
        lbl.text = "StreamProbe"
        lbl.font = .systemFont(ofSize: 12, weight: .bold)
        lbl.textColor = .white
        lbl.translatesAutoresizingMaskIntoConstraints = false
        return lbl
    }()

    let collapseButton: UIButton = {
        let btn = UIButton(type: .system)
        btn.setTitle("▾", for: .normal)
        btn.tintColor = .white
        btn.translatesAutoresizingMaskIntoConstraints = false
        return btn
    }()

    let errorIndicatorButton: UIButton = {
        let btn = UIButton(type: .system)
        btn.tintColor = .systemYellow
        btn.titleLabel?.font = .systemFont(ofSize: 11, weight: .semibold)
        btn.isHidden = true
        btn.translatesAutoresizingMaskIntoConstraints = false
        return btn
    }()

    // Initialized in setup() after super.init so tableView lazy var is safe to access
    var tableHeightConstraint: NSLayoutConstraint!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func setup() {
        backgroundColor = UIColor.black.withAlphaComponent(0.82)
        layer.cornerRadius = 12
        layer.masksToBounds = true
        translatesAutoresizingMaskIntoConstraints = false

        // Sub-view references for layout
        let chipWrapper = chipBar
        chipWrapper.translatesAutoresizingMaskIntoConstraints = false
        let statsWrapper = statsView
        statsWrapper.translatesAutoresizingMaskIntoConstraints = false

        // Header row
        let headerRow = UIView()
        headerRow.translatesAutoresizingMaskIntoConstraints = false
        headerRow.addSubview(headerLabel)
        headerRow.addSubview(errorIndicatorButton)
        headerRow.addSubview(collapseButton)

        NSLayoutConstraint.activate([
            headerLabel.leadingAnchor.constraint(equalTo: headerRow.leadingAnchor, constant: 10),
            headerLabel.centerYAnchor.constraint(equalTo: headerRow.centerYAnchor),
            errorIndicatorButton.centerXAnchor.constraint(equalTo: headerRow.centerXAnchor),
            errorIndicatorButton.centerYAnchor.constraint(equalTo: headerRow.centerYAnchor),
            collapseButton.trailingAnchor.constraint(equalTo: headerRow.trailingAnchor, constant: -8),
            collapseButton.centerYAnchor.constraint(equalTo: headerRow.centerYAnchor),
            headerRow.heightAnchor.constraint(equalToConstant: 36),
        ])

        tableHeightConstraint = tableView.heightAnchor.constraint(equalToConstant: 200)
        tableHeightConstraint.priority = .defaultHigh

        let stack = UIStackView(arrangedSubviews: [headerRow, chipWrapper, statsWrapper, tableView])
        stack.axis = .vertical
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            chipWrapper.heightAnchor.constraint(equalToConstant: 44).with(priority: .defaultHigh),
            statsWrapper.heightAnchor.constraint(greaterThanOrEqualToConstant: 0).with(priority: .defaultHigh),
            tableHeightConstraint,
        ])

        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:))))
    }

    @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
        guard let superview = superview else { return }
        let translation = pan.translation(in: superview)
        center = CGPoint(x: center.x + translation.x, y: center.y + translation.y)
        pan.setTranslation(.zero, in: superview)
    }

    func applyCollapsed(_ isCollapsed: Bool) {
        chipBar.isHidden = isCollapsed
        statsView.isHidden = isCollapsed
        tableView.isHidden = isCollapsed
        collapseButton.setTitle(isCollapsed ? "▸" : "▾", for: .normal)
    }

    func applyErrorIndicator(_ indicator: ErrorIndicatorState?) {
        if let ind = indicator {
            errorIndicatorButton.setTitle(ind.text, for: .normal)
            errorIndicatorButton.isHidden = false
        } else {
            errorIndicatorButton.isHidden = true
        }
    }
}

private extension NSLayoutConstraint {
    func with(priority: UILayoutPriority) -> NSLayoutConstraint {
        self.priority = priority
        return self
    }
}
