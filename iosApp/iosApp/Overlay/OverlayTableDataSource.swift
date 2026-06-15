import UIKit
import StreamProbe

final class OverlayTableDataSource: NSObject, UITableViewDataSource, UITableViewDelegate {
    private var state: OverlayViewState?

    func update(_ newState: OverlayViewState, tableView: UITableView) {
        state = newState
        tableView.reloadData()
    }

    // MARK: - UITableViewDataSource

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

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        guard let s = state else { return cell }
        cell.backgroundColor = .clear
        cell.textLabel?.textColor = .white
        cell.textLabel?.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        cell.textLabel?.numberOfLines = 2
        cell.selectionStyle = .none

        switch s.mode {
        case .tracks:
            cell.textLabel?.text = tracksText(s.lists.renditionRows[indexPath.row])
        case .segments:
            cell.textLabel?.text = segmentText(s.lists.segments[indexPath.row])
        case .switches:
            cell.textLabel?.text = switchText(s.lists.switches[indexPath.row])
        case .drm:
            cell.textLabel?.text = drmText(s.lists.drmEvents[indexPath.row])
        case .errors:
            cell.textLabel?.text = errorText(s.lists.errors[indexPath.row])
        }
        return cell
    }

    // MARK: - Row formatters

    /* SKIE 0.10.x exposes sealed interfaces via onEnum(of:) → __Sealed frozen enum.
       Switch on onEnum(of: row) to get exhaustive .audio / .sectionHeader / .subtitle / .video cases. */
    private func tracksText(_ row: any OverlayRow) -> String {
        switch onEnum(of: row) {
        case .sectionHeader(let header):
            return "── \(header.title) ──"
        case .video(let videoRow):
            let info = videoRow.info
            let sel = info.isSelected ? "▶" : " "
            let kbps = info.bitrate / 1000
            return "\(sel) \(info.width)×\(info.height) \(kbps)kbps"
        case .audio(let audioRow):
            let info = audioRow.info
            let sel = info.isSelected ? "▶" : " "
            let lang = info.language ?? info.label ?? "audio"
            let kbps = info.bitrate / 1000
            return "\(sel) \(lang) \(kbps)kbps\(info.isMuxed ? " [muxed]" : "")"
        case .subtitle(let subtitleRow):
            let info = subtitleRow.info
            let sel = info.isSelected ? "▶" : " "
            let lang = info.language ?? info.label ?? "subtitle"
            return "\(sel) \(lang) [\(info.kind.name)]"
        }
    }

    private func segmentText(_ seg: SegmentMetric) -> String {
        let kb = seg.sizeBytes / 1024
        let kbps = seg.throughputBytesPerSec > 0 ? " \(seg.throughputBytesPerSec * 8 / 1000)kbps" : ""
        let filename = (seg.uri as NSString).lastPathComponent
        return "\(kb)KB\(kbps) \(filename)"
    }

    /* TrackSwitchEvent sealed hierarchy via onEnum(of:) → .audioSwitch / .subtitleSwitch / .videoSwitch */
    private func switchText(_ event: any TrackSwitchEvent) -> String {
        switch onEnum(of: event) {
        case .videoSwitch(let v):
            let from = v.previousTrack.map { "\($0.width)×\($0.height)" } ?? "?"
            let to = "\(v.newTrack.width)×\(v.newTrack.height)"
            return "▶ Video \(from)→\(to) [\(v.reason.name)]"
        case .audioSwitch(let a):
            let from = a.previousTrack?.label ?? a.previousTrack?.language ?? "?"
            let to = a.newTrack.label ?? a.newTrack.language ?? "?"
            return "♪ Audio \(from)→\(to) [\(a.reason.name)]"
        case .subtitleSwitch(let sub):
            let from = sub.previousTrack?.label ?? sub.previousTrack?.language ?? "?"
            let to = sub.newTrack?.label ?? sub.newTrack?.language ?? "off"
            return "CC \(from)→\(to) [\(sub.reason.name)]"
        }
    }

    /* DrmSessionEvent sealed hierarchy via onEnum(of:) → .keysLoaded / .sessionAcquired / .sessionError / .sessionReleased */
    private func drmText(_ event: any DrmSessionEvent) -> String {
        switch onEnum(of: event) {
        case .sessionAcquired(let e):
            return "🔑 Acquired [\(e.scheme.name)] state=\(e.state.name)"
        case .keysLoaded(let e):
            return "✓ Keys loaded [\(e.scheme.name)] \(e.licenseLatencyMs)ms"
        case .sessionError(let e):
            return "✗ Error [\(e.scheme.name)] \(e.message)"
        case .sessionReleased(let e):
            return "○ Released [\(e.scheme.name)]"
        }
    }

    private func errorText(_ event: PlaybackErrorEvent) -> String {
        return "[\(event.category.name)] \(event.message)"
    }
}
