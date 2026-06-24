import SwiftUI

/// Custom playback controls overlaid on the video: seek ±10s, play/pause, a scrubber with
/// buffered-progress track, position/remaining labels, and an exit button. Stateless beyond the
/// bound view-model; visibility is owned by `PlayerScreen`.
struct PlayerControlsView: View {
    @ObservedObject var viewModel: PlayerViewModel
    let onExit: () -> Void

    var body: some View {
        ZStack {
            // Exit button — top-leading.
            VStack {
                HStack {
                    Button(action: onExit) {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .accessibilityIdentifier(A11y.Player.exit)
                    Spacer()
                }
                .padding(.leading, 16)
                .padding(.top, 8)
                Spacer()
            }

            // Center transport row.
            HStack(spacing: 28) {
                transportButton("gobackward.10", id: A11y.Player.seekBack, action: viewModel.seekBack)
                transportButton(viewModel.isPlaying ? "pause.fill" : "play.fill",
                                id: A11y.Player.playPause, size: 34, action: viewModel.togglePlayPause)
                transportButton("goforward.10", id: A11y.Player.seekForward, action: viewModel.seekForward)
            }

            if viewModel.isBuffering {
                ProgressView().progressViewStyle(.circular).tint(.white).scaleEffect(1.4)
            }

            // Bottom scrubber + time labels.
            VStack {
                Spacer()
                VStack(spacing: 6) {
                    ScrubberView(viewModel: viewModel)
                    HStack {
                        Text(viewModel.positionText)
                            .accessibilityIdentifier(A11y.Player.positionLabel)
                        Spacer()
                        Text(viewModel.remainingText)
                            .accessibilityIdentifier(A11y.Player.durationLabel)
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundColor(.white)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
                .background(
                    LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                )
            }
        }
    }

    private func transportButton(_ systemName: String, id: String, size: CGFloat = 26,
                                 action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 58, height: 58)
                .background(Color.black.opacity(0.5))
                .clipShape(Circle())
        }
        .accessibilityIdentifier(id)
    }
}

/// Scrubber: a `Slider` bound to a local drag value, gated through the view-model's scrub state
/// so the periodic time observer can't fight the user's drag. Buffered progress is drawn behind it.
private struct ScrubberView: View {
    @ObservedObject var viewModel: PlayerViewModel
    @State private var dragValue: Double = 0

    var body: some View {
        let upperBound = max(viewModel.duration, 1)
        return ZStack(alignment: .leading) {
            GeometryReader { geo in
                Capsule().fill(Color.white.opacity(0.25))
                Capsule().fill(Color.white.opacity(0.45))
                    .frame(width: geo.size.width * CGFloat(viewModel.bufferedFraction))
            }
            .frame(height: 4)

            Slider(
                value: Binding(
                    get: { viewModel.scrubState == .scrubbing ? dragValue : viewModel.currentTime },
                    set: { dragValue = $0 }
                ),
                in: 0...upperBound,
                onEditingChanged: { editing in
                    if editing {
                        dragValue = viewModel.currentTime
                        viewModel.beginScrub()
                    } else {
                        viewModel.commitScrub(to: dragValue)
                    }
                }
            )
            .tint(.white)
            .accessibilityIdentifier(A11y.Player.scrubber)
        }
    }
}
