import UIKit
import StreamProbe

/// The draggable overlay panel: rounded navy background, header, and a collapsible body.
/// Body is a vertical stack (portrait) or horizontal split (landscape).
final class OverlayPanelView: UIView {

    let header = HeaderView()
    let statsView = StatsView()
    let chipBar = ChipBarView()
    let errorsHeader = ErrorsHeaderView()
    let tableView = UITableView()

    private let bodyContainer = UIView()
    private var bodyStack: UIStackView!          // axis flips on orientation
    private let leftColumn = UIStackView()        // stats
    private let rightColumn = UIStackView()       // chip/errors header + table
    private var tableHeightConstraint: NSLayoutConstraint!
    private var maxTableHeight: CGFloat = 180

    private(set) var isLandscape: Bool

    init(isLandscape: Bool) {
        self.isLandscape = isLandscape
        super.init(frame: .zero)
        backgroundColor = OverlayTheme.panelBg
        layer.cornerRadius = OverlayTheme.panelCorner
        layer.masksToBounds = true
        translatesAutoresizingMaskIntoConstraints = false

        configureTable()
        buildColumns()
        buildBody()
    }
    required init?(coder: NSCoder) { fatalError() }

    private func configureTable() {
        tableView.backgroundColor = .clear
        tableView.showsVerticalScrollIndicator = true
        tableView.translatesAutoresizingMaskIntoConstraints = false
        // UITableView has no intrinsic content height, so it must be given a definite
        // height; we set it to min(content, cap) and refresh after every reload.
        tableHeightConstraint = tableView.heightAnchor.constraint(equalToConstant: 0)
        tableHeightConstraint.priority = .required
        tableHeightConstraint.isActive = true
    }

    private func buildColumns() {
        leftColumn.axis = .vertical
        leftColumn.translatesAutoresizingMaskIntoConstraints = false
        leftColumn.addArrangedSubview(statsView)

        rightColumn.axis = .vertical
        rightColumn.spacing = 6
        rightColumn.translatesAutoresizingMaskIntoConstraints = false
        rightColumn.addArrangedSubview(chipBar)
        rightColumn.addArrangedSubview(errorsHeader)
        rightColumn.addArrangedSubview(tableView)
        errorsHeader.isHidden = true
    }

    private func buildBody() {
        bodyStack = UIStackView()
        bodyStack.axis = isLandscape ? .horizontal : .vertical
        bodyStack.spacing = 12
        // Landscape: top-align the columns (chips line up with the first stat row, like Android).
        // Portrait: fill so each column spans the full panel width.
        bodyStack.alignment = isLandscape ? .top : .fill
        bodyStack.distribution = isLandscape ? .fillEqually : .fill
        bodyStack.translatesAutoresizingMaskIntoConstraints = false
        bodyStack.addArrangedSubview(leftColumn)
        bodyStack.addArrangedSubview(rightColumn)

        bodyContainer.translatesAutoresizingMaskIntoConstraints = false
        bodyContainer.addSubview(bodyStack)
        NSLayoutConstraint.activate([
            bodyStack.topAnchor.constraint(equalTo: bodyContainer.topAnchor, constant: 10),
            bodyStack.bottomAnchor.constraint(equalTo: bodyContainer.bottomAnchor, constant: -14),
            bodyStack.leadingAnchor.constraint(equalTo: bodyContainer.leadingAnchor, constant: 14),
            bodyStack.trailingAnchor.constraint(equalTo: bodyContainer.trailingAnchor, constant: -14),
        ])

        let root = UIStackView(arrangedSubviews: [header, bodyContainer])
        root.axis = .vertical
        root.translatesAutoresizingMaskIntoConstraints = false
        addSubview(root)
        NSLayoutConstraint.activate([
            root.topAnchor.constraint(equalTo: topAnchor),
            root.bottomAnchor.constraint(equalTo: bottomAnchor),
            root.leadingAnchor.constraint(equalTo: leadingAnchor),
            root.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    /// Caps the list height (180pt portrait; 55% of screen clamped 200–360 landscape).
    func setTableMaxHeight(_ height: CGFloat) {
        maxTableHeight = height
        refreshTableHeight()
    }

    /// Sizes the table to its content, capped at `maxTableHeight` (scrolls beyond).
    /// Must be called after the table's data reloads.
    func refreshTableHeight() {
        tableView.layoutIfNeeded()
        let content = tableView.contentSize.height
        tableHeightConstraint.constant = min(max(content, 0), maxTableHeight)
    }

    /// Flip the body axis when orientation changes.
    func setLandscape(_ landscape: Bool) {
        isLandscape = landscape
        bodyStack.axis = landscape ? .horizontal : .vertical
        bodyStack.alignment = landscape ? .top : .fill
        bodyStack.distribution = landscape ? .fillEqually : .fill
        leftColumn.isHidden = false
    }

    func applyCollapsed(_ isCollapsed: Bool) {
        bodyContainer.isHidden = isCollapsed
        header.applyCollapsed(isCollapsed)
    }

    /// Show chip bar in normal mode, errors header in errors mode.
    func applyErrorsMode(_ isErrorsMode: Bool) {
        chipBar.isHidden = isErrorsMode
        errorsHeader.isHidden = !isErrorsMode
    }
}
