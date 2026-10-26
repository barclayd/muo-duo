import SwiftUI

struct ContentView: View {
    let model: StatusModel

    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("finish") private var finish = Finish.nightSky
    @State private var showingSettings = false
    @State private var showingLog = false
    @State private var showingActivity = false
    @Namespace private var ticker
    @State private var map: MapFocus?

    var body: some View {
        NavigationStack {
            Group {
                if let status = model.status {
                    home(status)
                } else if let error = model.errorMessage {
                    ContentUnavailableView {
                        Label("Can't reach the checker", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Try again") { Task { await model.refresh() } }
                            .buttonStyle(.glass)
                    }
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { Backdrop(finish: finish) }
            .navigationDestination(isPresented: $showingSettings) { SettingsPage(model: model, finish: finish) }
            .navigationDestination(isPresented: $showingLog) { StockLogPage(model: model, finish: finish) }
            .toolbar {
                if let status = model.status {
                    ToolbarItem(placement: .topBarLeading) {
                        LiveBadge(model: model, status: status)
                    }
                    // The badge brings its own glass, so it can turn into a solid pill when something's wrong.
                    .sharedBackgroundVisibility(.hidden)
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("Store map", systemImage: "map") { map = MapFocus(area: nil) }
                        Button("Settings", systemImage: "gearshape") { showingSettings = true }
                    }
                }
            }
        }
        .preferredColorScheme(finish.colorScheme)
        // The Worker does the checking; the app only watches while it's on screen.
        .task(id: scenePhase) {
            if scenePhase == .active { await model.poll() }
        }
        .fullScreenCover(item: $map) { map in StoreMap(model: model, finish: finish, focus: map.area) }
        .fullScreenCover(isPresented: $showingActivity) {
            ActivityPage(model: model, finish: finish)
                .navigationTransition(.zoom(sourceID: "ticker", in: ticker))
        }
    }

    private func home(_ status: Status) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Hero(model: model, status: status, finish: $finish)
                    .padding(.bottom, 8)
                // Straight under the colour picker, so a sighting is the first thing on screen.
                InStockCard(model: model, status: status, finish: finish)
                HealthBanner(status: status, errorMessage: model.errorMessage)
                LastSeenCard(status: status, finish: finish)
                StockLogCard(status: status) { showingLog = true }
                StoresCard(model: model, status: status, finish: finish) { map = MapFocus(area: $0) }
                SlowedBanner(status: status)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
            .animation(.smooth, value: model.errorMessage)
            .animation(.smooth, value: status.state)
            .animation(.smooth, value: status.sightings)
            .sensoryFeedback(.success, trigger: status.sightings.count) { old, new in new > old }
        }
        .scrollIndicators(.hidden)
        .refreshable { await model.refresh() }
        .safeAreaInset(edge: .bottom) {
            Ticker(model: model, status: status, finish: finish) { showingActivity = true }
                .matchedTransitionSource(id: "ticker", in: ticker)
        }
    }
}

private struct MapFocus: Identifiable {
    let area: String?
    var id: String { area ?? "" }
}
