import SwiftUI

struct ContentView: View {
    let model: StatusModel

    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("finish") private var finish = Finish.nightSky
    @State private var showingSettings = false
    @State private var showingLog = false
    @State private var pane: Pane?
    @State private var fullScreen = false
    @State private var room = Room.narrow

    /// Home's column on the open iPhone Duo. The page beside it gets the rest.
    private static let homeWidth: CGFloat = 400
    /// The phone and its colours, stood up like a tent. The cards get the rest.
    private static let tentHeroWidth: CGFloat = 220

    private var wide: Bool { room == .wide }

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
            // Open, iPhone Duo is wider than it is tall, with room for home and a page side by side. Stood up like a
            // tent it's wide but short.
            .onGeometryChange(for: Room.self) { Room($0.size) } action: { room = $0 }
            .environment(\.room, room)
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
            .barStaysPut(wide)
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

    /// Narrow, everything. Open, the hero and anything urgent; the rest goes beside it. Stood up like a tent, the
    /// same cards in the same order, beside the hero rather than under it.
    private func home(_ status: Status) -> some View {
        Group {
            if room == .tent {
                HStack(alignment: .top, spacing: 16) {
                    // Only scrolls if larger text pushes the picker off the bottom.
                    ScrollView {
                        Hero(model: model, status: status, finish: $finish)
                            .padding(.bottom, 16)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    // The radar spreads past the column, behind the cards.
                    .scrollClipDisabled()
                    .frame(width: Self.tentHeroWidth)

                    ScrollView {
                        cards(status)
                            .padding(.top, 8)
                            .padding(.bottom, 16)
                    }
                    .refreshable { await model.refresh() }
                    // Under the cards rather than across both columns, where it would cover the colour picker.
                    .safeAreaInset(edge: .bottom) { ticker(status) }
                }
                .padding(.horizontal, 16)
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        Hero(model: model, status: status, finish: $finish)
                            .padding(.bottom, 8)
                        cards(status)
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
                // Open, the bar holds nothing but the live badge, which brings its own glass. Cards scroll off the
                // top rather than under a band of glass.
                .scrollEdgeEffectHidden(wide, for: .top)
                .refreshable { await model.refresh() }
                .safeAreaInset(edge: .bottom) { ticker(status) }
            }
        }
        .scrollIndicators(.hidden)
        .animation(.smooth, value: model.errorMessage)
        .animation(.smooth, value: status.state)
        .animation(.smooth, value: status.sightings)
        .sensoryFeedback(.success, trigger: status.sightings.count) { old, new in new > old }
    }

    @ViewBuilder private func ticker(_ status: Status) -> some View {
        // Live activity open beside it already says what the ticker would.
        if pane != .activity {
            Ticker(model: model, status: status, finish: finish) { open(.activity) }
        }
    }

    /// Under the hero, or beside it in a tent.
    private func cards(_ status: Status) -> some View {
        VStack(spacing: 16) {
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
        .scrollEdgeEffectHidden(true, for: .top)
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
