import UIKit
import StreamProbeCore

/// Drives the overlay table for all five tabs. Owns error-row expansion state and
/// auto-scroll-to-newest behavior (scroll to last row unless the user scrolled up).
///
/// Expansion is keyed by the error's `timestampMs` (not IndexPath) so it survives
/// list reloads.
final class OverlayTableDataSource: NSObject, UITableViewDataSource, UITableViewDelegate {

    private var state: OverlayViewState?
    private var expandedTimestampMs: Int64?
    private weak var tableView: UITableView?

    /// True while the last row was visible before the latest update (drives auto-scroll).
    private var wasPinnedToBottom = true

    func register(_ tableView: UITableView) {
        self.tableView = tableView
        tableView.register(RenditionSectionHeaderCell.self, forCellReuseIdentifier: RenditionSectionHeaderCell.reuseID)
        tableView.register(RenditionItemCell.self, forCellReuseIdentifier: RenditionItemCell.reuseID)
        tableView.register(SegmentCell.self, forCellReuseIdentifier: SegmentCell.reuseID)
        tableView.register(SwitchCell.self, forCellReuseIdentifier: SwitchCell.reuseID)
        tableView.register(ErrorCell.self, forCellReuseIdentifier: ErrorCell.reuseID)
        tableView.register(DrmCell.self, forCellReuseIdentifier: DrmCell.reuseID)
        tableView.dataSource = self
        tableView.delegate = self
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 44
        tableView.separatorStyle = .none
    }

    func update(_ newState: OverlayViewState) {
        wasPinnedToBottom = isPinnedToBottom()
        state = newState
        // Clear expansion if the expanded error is no longer present (mirrors Android onCurrentListChanged).
        if let ts = expandedTimestampMs,
           !newState.lists.errors.contains(where: { $0.timestampMs == ts }) {
            expandedTimestampMs = nil
        }
        tableView?.reloadData()
        if shouldAutoScroll(for: newState.mode) {
            scrollToBottomIfPinned()
        }
    }

    // MARK: - Counts

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard let s = state else { return 0 }
        switch s.mode {
        case .tracks:   return s.lists.renditionRows.count
        case .segments: return s.lists.segments.count
        case .switches: return s.lists.switches.count
        case .drm:      return s.lists.drmEvents.count
        case .errors:   return s.lists.errors.count
        }
    }

    // MARK: - Cells

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        guard let s = state else { return UITableViewCell() }
        switch s.mode {
        case .tracks:   return tracksCell(tableView, indexPath, s.lists.renditionRows[indexPath.row])
        case .segments: return segmentCell(tableView, indexPath, s.lists.segments[indexPath.row])
        case .switches: return switchCell(tableView, indexPath, s.lists.switches[indexPath.row], s)
        case .drm:      return drmCell(tableView, indexPath, s.lists.drmEvents[indexPath.row], s)
        case .errors:   return errorCell(tableView, indexPath, s.lists.errors[indexPath.row], s)
        }
    }

    private func tracksCell(_ tv: UITableView, _ ip: IndexPath, _ row: any OverlayRow) -> UITableViewCell {
        if case .sectionHeader(let h) = onEnum(of: row) {
            let cell = tv.dequeueReusableCell(withIdentifier: RenditionSectionHeaderCell.reuseID, for: ip) as! RenditionSectionHeaderCell
            cell.bind(h.title)
            return cell
        }
        let cell = tv.dequeueReusableCell(withIdentifier: RenditionItemCell.reuseID, for: ip) as! RenditionItemCell
        cell.bind(row)
        return cell
    }

    private func segmentCell(_ tv: UITableView, _ ip: IndexPath, _ metric: SegmentMetric) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: SegmentCell.reuseID, for: ip) as! SegmentCell
        cell.bind(index: ip.row, metric: metric)
        return cell
    }

    private func switchCell(_ tv: UITableView, _ ip: IndexPath, _ event: any TrackSwitchEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: SwitchCell.reuseID, for: ip) as! SwitchCell
        let base = s.lists.switches.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base)
        return cell
    }

    private func drmCell(_ tv: UITableView, _ ip: IndexPath, _ event: any DrmSessionEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: DrmCell.reuseID, for: ip) as! DrmCell
        let base = s.lists.drmEvents.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base)
        return cell
    }

    private func errorCell(_ tv: UITableView, _ ip: IndexPath, _ event: PlaybackErrorEvent, _ s: OverlayViewState) -> UITableViewCell {
        let cell = tv.dequeueReusableCell(withIdentifier: ErrorCell.reuseID, for: ip) as! ErrorCell
        let base = s.lists.errors.first?.timestampMs ?? 0
        cell.bind(index: ip.row, event: event, baseTimestampMs: base, expanded: event.timestampMs == expandedTimestampMs)
        return cell
    }

    // MARK: - Expand (errors only)

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard state?.mode == .errors,
              let errors = state?.lists.errors,
              indexPath.row < errors.count else { return }
        let ts = errors[indexPath.row].timestampMs
        expandedTimestampMs = (expandedTimestampMs == ts) ? nil : ts
        tableView.reloadData()
    }

    // MARK: - Auto-scroll

    private func shouldAutoScroll(for mode: ViewMode) -> Bool {
        // Timeline tabs append newest at the end; Tracks does not auto-scroll.
        mode == .segments || mode == .switches || mode == .drm || mode == .errors
    }

    private func isPinnedToBottom() -> Bool {
        guard let tv = tableView, tv.contentSize.height > 0 else { return true }
        let bottomEdge = tv.contentOffset.y + tv.bounds.height
        return bottomEdge >= tv.contentSize.height - 24 // ~2 rows of slack
    }

    private func scrollToBottomIfPinned() {
        guard wasPinnedToBottom, let tv = tableView else { return }
        let rows = tableView(tv, numberOfRowsInSection: 0)
        guard rows > 0 else { return }
        DispatchQueue.main.async {
            tv.scrollToRow(at: IndexPath(row: rows - 1, section: 0), at: .bottom, animated: false)
        }
    }
}
