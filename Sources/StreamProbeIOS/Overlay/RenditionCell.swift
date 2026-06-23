import UIKit
import StreamProbeCore

/// Section header row ("VIDEO" / "AUDIO" / "SUBTITLES") in the Tracks list.
final class RenditionSectionHeaderCell: UITableViewCell {
    static let reuseID = "RenditionSectionHeaderCell"
    private let label = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        label.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            label.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            label.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4),
            label.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(_ title: String) {
        label.attributedText = NSAttributedString(
            string: title,
            attributes: [
                .font: UIFont.systemFont(ofSize: 10, weight: .bold),
                .foregroundColor: OverlayTheme.white50,
                .kern: 1.0,
            ])
    }
}

/// Video / audio / subtitle item: dot + top line + (optional) bottom line.
final class RenditionItemCell: UITableViewCell {
    static let reuseID = "RenditionItemCell"
    private let dot = UIView()
    private let topLine = UILabel()
    private let bottomLine = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none

        dot.translatesAutoresizingMaskIntoConstraints = false
        dot.layer.cornerRadius = 4
        topLine.font = .systemFont(ofSize: 12, weight: .medium)
        topLine.textColor = OverlayTheme.white100
        topLine.numberOfLines = 0
        topLine.translatesAutoresizingMaskIntoConstraints = false
        bottomLine.font = .systemFont(ofSize: 10)
        bottomLine.textColor = OverlayTheme.white40
        bottomLine.numberOfLines = 0
        bottomLine.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(dot)
        contentView.addSubview(topLine)
        contentView.addSubview(bottomLine)

        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),
            dot.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            dot.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 9),

            topLine.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 8),
            topLine.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            topLine.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),

            bottomLine.leadingAnchor.constraint(equalTo: topLine.leadingAnchor),
            bottomLine.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            bottomLine.topAnchor.constraint(equalTo: topLine.bottomAnchor, constant: 2),
            bottomLine.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func bind(_ row: any OverlayRow) {
        switch onEnum(of: row) {
        case .video(let r):    bindVideo(r.info)
        case .audio(let r):    bindAudio(r.info)
        case .subtitle(let r): bindSubtitle(r.info)
        case .sectionHeader:   break // handled by RenditionSectionHeaderCell
        }
    }

    private func setDot(active: Bool) {
        dot.backgroundColor = active ? OverlayTheme.activeGreen : OverlayTheme.inactiveDot
    }

    private func bindVideo(_ info: VariantInfo) {
        setDot(active: info.isSelected)
        topLine.text = "\(OverlayFormattersSwift.formatResolution(info.width, info.height))  ·  \(OverlayFormattersSwift.formatBitrate(info.bitrate))"
        let codecs = info.codecs ?? ""
        bottomLine.text = codecs
        bottomLine.isHidden = codecs.isEmpty
    }

    private func bindAudio(_ info: AudioTrackInfo) {
        setDot(active: info.isSelected)
        var top: [String] = []
        let name = info.label ?? info.language.flatMap { OverlayFormattersSwift.resolveDisplayName($0) }
        if let name, !name.isEmpty { top.append(name) }
        if let ch = OverlayFormattersSwift.channelLabel(info.channelCount) { top.append(ch) }
        if info.bitrate > 0 { top.append(OverlayFormattersSwift.formatBitrate(info.bitrate)) }
        topLine.text = top.isEmpty ? "Audio" : top.joined(separator: "  ·  ")

        var bottom: [String] = []
        if let c = info.codecs { bottom.append(c) }
        if info.isMuxed { bottom.append("muxed") }
        bottomLine.text = bottom.joined(separator: "  ·  ")
        bottomLine.isHidden = bottom.isEmpty
    }

    private func bindSubtitle(_ info: SubtitleTrackInfo) {
        setDot(active: info.isSelected)
        var top: [String] = []
        let name = info.label ?? info.language.flatMap { OverlayFormattersSwift.resolveDisplayName($0) }
        if let name, !name.isEmpty { top.append(name) }
        if info.kind == .cc { top.append("(CC)") }
        topLine.text = top.isEmpty ? "Subtitle" : top.joined(separator: "  ")

        let mime = OverlayFormattersSwift.subtitleMimeShort(info.mimeType) ?? ""
        bottomLine.text = mime
        bottomLine.isHidden = mime.isEmpty
    }
}
