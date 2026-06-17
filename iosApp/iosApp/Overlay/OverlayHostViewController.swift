import UIKit
import StreamProbe

/// Hosts the overlay panel: positions it, drives the presenter→view render loop,
/// handles drag (from the header only), orientation rebuild, and share.
final class OverlayHostViewController: UIViewController {

    private let presenter: OverlayPresenter
    private let dataSource = OverlayTableDataSource()
    private var panel: OverlayPanelView!
    private var observationTask: Task<Void, Never>?
    private var latestErrors: [PlaybackErrorEvent] = []
    private var lastLandscape: Bool?

    init(presenter: OverlayPresenter) {
        self.presenter = presenter
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        buildPanel()
        startObserving()
    }

    /// Drives sizing from final bounds — reliable for both initial layout and rotation
    /// (the UIDevice orientation notification reflects device, not interface, orientation).
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let landscape = view.bounds.width > view.bounds.height
        if landscape != lastLandscape {
            applySizing()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        observationTask?.cancel()
        observationTask = nil
    }

    // MARK: - Panel construction / positioning

    private func buildPanel() {
        let landscape = view.bounds.width > view.bounds.height
        let p = OverlayPanelView(isLandscape: landscape)
        panel = p
        view.addSubview(p)
        dataSource.register(p.tableView)

        p.header.collapseButton.addTarget(self, action: #selector(collapseToggled), for: .touchUpInside)
        p.header.errorIndicator.addTarget(self, action: #selector(errorIndicatorTapped), for: .touchUpInside)
        p.errorsHeader.backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        p.errorsHeader.clearButton.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        p.errorsHeader.shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        p.chipBar.onChipSelected = { [weak self] mode in self?.presenter.onChipSelected(mode: mode) }

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        p.header.addGestureRecognizer(pan)

        applySizing()
    }

    private func applySizing() {
        let bounds = view.bounds
        guard bounds.width > 0, bounds.height > 0 else { return }
        let landscape = bounds.width > bounds.height
        lastLandscape = landscape
        panel.setLandscape(landscape)

        let width = min(bounds.width - 32, landscape ? 540 : 310)
        let tableMax: CGFloat = landscape
            ? min(max(bounds.height * 0.55, 200), 360)
            : 180
        panel.setTableMaxHeight(tableMax)

        panel.translatesAutoresizingMaskIntoConstraints = true
        panel.frame.size.width = width
        panel.setNeedsLayout()
        panel.layoutIfNeeded()
        let height = panel.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height)).height
        panel.frame = CGRect(
            x: bounds.width - width - 16,
            y: view.safeAreaInsets.top + 16,
            width: width, height: height)
    }

    // MARK: - Drag (header only), clamped to safe area

    @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
        let t = pan.translation(in: view)
        var newX = panel.frame.origin.x + t.x
        var newY = panel.frame.origin.y + t.y
        let insets = view.safeAreaInsets
        let minX = insets.left
        let maxX = max(view.bounds.width - panel.frame.width - insets.right, minX)
        let minY = insets.top
        let maxY = max(view.bounds.height - panel.frame.height - insets.bottom, minY)
        newX = min(max(newX, minX), maxX)
        newY = min(max(newY, minY), maxY)
        panel.frame.origin = CGPoint(x: newX, y: newY)
        pan.setTranslation(.zero, in: view)
    }

    // MARK: - Intents

    @objc private func collapseToggled() { presenter.onCollapseToggled() }
    @objc private func errorIndicatorTapped() { presenter.onErrorIndicatorTapped() }
    @objc private func backTapped() { presenter.onBackPressed() }
    @objc private func clearTapped() { presenter.onClearErrorsClicked() }

    @objc private func shareTapped() {
        let base = latestErrors.first?.timestampMs ?? 0
        let text = OverlayFormattersSwift.formatErrorsForExport(latestErrors, baseTimestampMs: base)
        let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = panel.errorsHeader.shareButton
        present(vc, animated: true)
    }

    // MARK: - Render loop

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
        latestErrors = state.lists.errors
        panel.header.applyErrorIndicator(state.errorIndicator)
        panel.applyCollapsed(state.isCollapsed)
        panel.applyErrorsMode(state.isErrorsMode)
        panel.chipBar.setSelected(state.mode)
        panel.chipBar.setDrmVisible(state.stats.drmVisible)
        panel.statsView.render(state.stats)
        panel.errorsHeader.setTitle(state.errorsTitle)
        dataSource.update(state)

        // Resize panel height to fit content (width/position unchanged).
        // Table has no intrinsic height — size it to its content first.
        panel.refreshTableHeight()
        panel.setNeedsLayout()
        panel.layoutIfNeeded()
        let fitted = panel.systemLayoutSizeFitting(
            CGSize(width: panel.frame.width, height: UIView.layoutFittingCompressedSize.height)).height
        panel.frame.size.height = fitted
    }
}
