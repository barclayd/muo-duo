import SwiftUI

struct ContentView: View {
    let model: StatusModel

    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("finish") private var finish = Finish.nightSky
    @State private var showingSettings = false
    @State private var showingLog = false
    @State private var pane: Pane?
    @State private var fullScreen = false
    @State private var wide = false

    /// Home's column on the open iPhone Duo. The page beside it gets the rest.
    private static let homeWidth: CGFloat = 400

    var body: some View {
        NavigationStack {
            Group {
                if let status = model.status {
                    panes(status)
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
            // Open, iPhone Duo is wider than it is tall, with room for home and a page side by side.
            .onGeometryChange(for: Bool.self) { $0.size.width >= 700 } action: { wide = $0 }
            .background { Backdrop(finish: finish) }
            .navigationDestination(isPresented: $showingSettings) { SettingsPage(model: model, finish: finish) }
            .navigationDestination(isPresented: $showingLog) { StockLogPage(model: model, finish: finish) }
            .toolbar {
                // A page with the screen to itself brings its own bar.
                if let status = model.status, pane == nil || wide {
                    ToolbarItem(placement: .topBarLeading) {
                        LiveBadge(model: model, status: status)
                    }
                    // The badge brings its own glass, so it can turn into a solid pill when something's wrong.
                    .sharedBackgroundVisibility(.hidden)
                    // On iPhone Duo these move to the rail down the side of the screen.
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("Store map", systemImage: "map") { open(.map(MapFocus())) }
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
    }

    /// Home stays put underneath, hidden rather than removed, so it's where it was when the page closes. Folding
    /// or unfolding only changes how much of the screen each one gets.
    private func panes(_ status: Status) -> some View {
        let showsHome = pane == nil || wide && !fullScreen
        return ZStack(alignment: .topLeading) {
            home(status)
                .frame(width: wide ? Self.homeWidth : nil)
                .opacity(showsHome ? 1 : 0)
                .allowsHitTesting(showsHome)
                .accessibilityHidden(!showsHome)

            Group {
                if let pane {
                    page(pane)
                        .transition(.move(edge: wide ? .trailing : .bottom))
                } else if wide {
                    details(status)
                        .transition(.opacity)
                }
            }
            .padding(.leading, wide && showsHome ? Self.homeWidth : 0)
        }
    }

    /// Narrow, everything. Open, the hero and anything urgent; the rest goes beside it.
    private func home(_ status: Status) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Hero(model: model, status: status, finish: $finish)
                    .padding(.bottom, 8)
                // Straight under the colour picker, so a sighting is the first thing on screen.
                InStockCard(model: model, status: status, finish: finish) { open(.map(MapFocus(store: $0))) }
                HealthBanner(status: status, errorMessage: model.errorMessage)
                if !wide || pane != nil {
                    LastSeenCard(status: status, finish: finish)
                }
                if !wide {
                    StockLogCard(status: status) { showingLog = true }
                    StoresCard(model: model, status: status, finish: finish) { open(.map(MapFocus(area: $0))) }
                    SlowedBanner(status: status)
                }
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
            // Live activity open beside it already says what the ticker would.
            if pane != .activity {
                Ticker(model: model, status: status, finish: finish) { open(.activity) }
            }
        }
    }

    /// Beside home on the open iPhone Duo, when nothing else is open.
    private func details(_ status: Status) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                LastSeenCard(status: status, finish: finish)
                StockLogCard(status: status) { showingLog = true }
                StoresCard(model: model, status: status, finish: finish) { open(.map(MapFocus(area: $0))) }
                SlowedBanner(status: status)
            }
            .padding(.trailing, 16)
            .padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .refreshable { await model.refresh() }
    }

    @ViewBuilder private func page(_ pane: Pane) -> some View {
        let chrome: PageChrome = wide ? .pane(fullScreen: $fullScreen) : .bar
        switch pane {
        case .map(let focus):
            // A new focus is a new map, flown straight there.
            StoreMap(model: model, finish: finish, focus: focus, chrome: chrome, close: close)
                .id(focus)
        case .activity:
            ActivityPage(model: model, finish: finish, chrome: chrome, close: close)
        }
    }

    private func open(_ pane: Pane) {
        withAnimation(.smooth) { self.pane = pane }
    }

    private func close() {
        withAnimation(.smooth) {
            pane = nil
            fullScreen = false
        }
    }
}
