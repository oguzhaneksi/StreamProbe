import UIKit
import StreamProbe

final class OverlayHostViewController: UIViewController {
    private let panel = OverlayPanelView()
    private let tableDataSource = OverlayTableDataSource()
    private let presenter: OverlayPresenter
    private var observationTask: Task<Void, Never>?

    init(presenter: OverlayPresenter) {
        self.presenter = presenter
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        panel.tableView.dataSource = tableDataSource
        panel.tableView.delegate = tableDataSource

        view.addSubview(panel)
        // Initial position: top-right, inset from safe area
        let panelWidth: CGFloat = min(view.bounds.width * 0.72, 320)
        panel.frame = CGRect(
            x: view.bounds.width - panelWidth - 16,
            y: 60,
            width: panelWidth,
            height: 360
        )

        wireUpButtons()
        startObserving()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        observationTask?.cancel()
        observationTask = nil
    }

    private func wireUpButtons() {
        panel.collapseButton.addTarget(self, action: #selector(collapseToggled), for: .touchUpInside)
        panel.errorIndicatorButton.addTarget(self, action: #selector(errorIndicatorTapped), for: .touchUpInside)
        panel.chipBar.onChipSelected = { [weak self] mode in
            self?.presenter.onChipSelected(mode: mode)
        }
    }

    @objc private func collapseToggled() { presenter.onCollapseToggled() }
    @objc private func errorIndicatorTapped() { presenter.onErrorIndicatorTapped() }

    private func startObserving() {
        observationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await state in self.presenter.viewState {
                self.render(state)
            }
        }
    }

    @MainActor
    private func render(_ state: OverlayViewState) {
        panel.applyCollapsed(state.isCollapsed)
        panel.applyErrorIndicator(state.errorIndicator)
        panel.chipBar.setSelected(mode: state.mode)
        panel.statsView.render(state.stats)
        tableDataSource.update(state, tableView: panel.tableView)

        // Drive height via the constraint, not the frame, to avoid fighting AutoLayout
        let tableHeight: CGFloat = state.isCollapsed ? 0 : 200
        panel.tableHeightConstraint?.constant = tableHeight
        UIView.animate(withDuration: 0.2) {
            self.panel.layoutIfNeeded()
        }
    }
}
