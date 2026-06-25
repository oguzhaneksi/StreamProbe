import StreamProbe
import SwiftUI

/// Root screen: a list of HLS streams (Android-parity cards with an HLS badge) plus a Settings
/// entry. Selecting a stream presents the fullscreen player as a cover.
struct StreamSelectionScreen: View {
    let probe: StreamProbe
    @ObservedObject var settings: SettingsStore

    @State private var selectedStream: Stream?
    @State private var showSettings = false

    var body: some View {
        NavigationView {
            List(demoStreams) { stream in
                Button { selectedStream = stream } label: { StreamRow(stream: stream) }
                    .listRowBackground(Color(white: 0.11))
                    .accessibilityIdentifier(A11y.StreamList.row(stream.title))
            }
            .listStyle(.plain)
            .background(Color.black)
            .navigationTitle("Select Stream")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape.fill") }
                        .accessibilityIdentifier(A11y.StreamList.settingsButton)
                }
            }
            .accessibilityIdentifier(A11y.StreamList.screen)
        }
        .navigationViewStyle(.stack)
        .fullScreenCover(item: $selectedStream) { stream in
            PlayerScreen(stream: stream, probe: probe, onExit: { selectedStream = nil },
                         settings: settings)
        }
        .sheet(isPresented: $showSettings) {
            SettingsScreen(settings: settings, probe: probe, onClose: { showSettings = false })
        }
    }
}

/// One stream row: title + a type badge — green "HLS" for VOD, red "● LIVE" for live.
private struct StreamRow: View {
    let stream: Stream
    var body: some View {
        HStack {
            Text(stream.title).foregroundColor(.white).fontWeight(.medium)
            Spacer()
            if stream.isLive {
                badge(text: "● LIVE", color: .red)
            } else {
                badge(text: "HLS", color: Color(red: 0.19, green: 0.82, blue: 0.35))
            }
        }
        .padding(.vertical, 6)
    }

    private func badge(text: String, color: Color) -> some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundColor(color)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(color.opacity(0.2))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
