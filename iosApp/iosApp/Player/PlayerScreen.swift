import AVFoundation
import StreamProbe
import SwiftUI

/// Fullscreen player: video surface + auto-hiding custom controls. Owns the player lifecycle —
/// attaches `StreamProbe`, starts/stops the overlay presenter, releases on exit — and bridges
/// `scenePhase` to pause/resume. The probe instance is shared (created in `AppDependencies`).
struct PlayerScreen: View {
    let stream: Stream
    let probe: StreamProbe
    let onExit: () -> Void

    @StateObject private var viewModel = PlayerViewModel(engine: AVPlayerEngine())
    @ObservedObject var settings: SettingsStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var controlsVisible = true
    @State private var hideWorkItem: DispatchWorkItem?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player = viewModel.avPlayer {
                VideoSurfaceView(player: player).ignoresSafeArea()
            }

            if controlsVisible {
                PlayerControlsView(viewModel: viewModel, onExit: exit)
                    .transition(.opacity)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { toggleControls() }
        .statusBarHidden(true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(A11y.Player.screen)
        .onAppear(perform: start)
        .onDisappear(perform: stop)
        .onChange(of: viewModel.isPlaying) { _ in scheduleAutoHide() }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: if settings.autoPlay { viewModel.play() }
            case .inactive, .background: viewModel.pause()
            @unknown default: break
            }
        }
    }

    private func start() {
        configureAudioSession()
        viewModel.attach(streamURL: stream.url, autoPlay: settings.autoPlay, isLive: stream.isLive)
        if let player = viewModel.avPlayer {
            probe.attach(player: player)
            probe.show()
        }
        scheduleAutoHide()
    }

    private func stop() {
        hideWorkItem?.cancel()
        probe.detach()
        viewModel.teardown()
    }

    private func exit() {
        stop()
        onExit()
    }

    private func toggleControls() {
        withAnimation { controlsVisible.toggle() }
        if controlsVisible { scheduleAutoHide() }
    }

    private func scheduleAutoHide() {
        hideWorkItem?.cancel()
        guard viewModel.isPlaying else { return }
        let item = DispatchWorkItem { withAnimation { controlsVisible = false } }
        hideWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: item)
    }

    private func configureAudioSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
    }
}
