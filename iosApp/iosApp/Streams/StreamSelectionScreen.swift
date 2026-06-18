import StreamProbe
import SwiftUI

/// Root screen: a list of HLS streams (Android-parity cards with an HLS badge) plus a Settings
/// entry. Selecting a stream presents the fullscreen player as a cover.
struct StreamSelectionScreen: View {
    let probe: StreamProbe_
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

/// One stream row: title + an HLS type badge (all entries are HLS in this demo).
private struct StreamRow: View {
    let stream: Stream
    var body: some View {
        HStack {
            Text(stream.title).foregroundColor(.white).fontWeight(.medium)
            Spacer()
            Text("HLS")
                .font(.caption.weight(.semibold))
                .foregroundColor(Color(red: 0.19, green: 0.82, blue: 0.35))
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color(red: 0.19, green: 0.82, blue: 0.35).opacity(0.2))
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .padding(.vertical, 6)
    }
}
