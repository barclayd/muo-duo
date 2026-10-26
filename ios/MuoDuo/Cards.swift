import MapKit
import SwiftUI

extension View {
    /// A pane of glass for a section of the home screen.
    func card(tint: Color? = nil) -> some View {
        padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(tint.map { .regular.tint($0.opacity(0.22)) } ?? .regular, in: .rect(cornerRadius: 28))
    }
}

struct CardHeader: View {
    let title: String
    let symbol: String
    var trailing: String?

    var body: some View {
        HStack {
            Label(title, systemImage: symbol)
                .font(.footnote.weight(.semibold))
                .textCase(.uppercase)
                .tracking(0.6)
            Spacer()
            if let trailing {
                Text(trailing).font(.footnote).monospacedDigit()
            }
        }
        .foregroundStyle(.secondary)
    }
}

// MARK: - Health

/// Only shown when something's wrong, so the home screen stays calm when it isn't.
struct HealthBanner: View {
    let status: Status
    let errorMessage: String?

    var body: some View {
        if let problem {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: problem.symbol)
                    .font(.title2)
                    .foregroundStyle(problem.color)
                    .symbolEffect(.pulse, options: .repeating)
                VStack(alignment: .leading, spacing: 3) {
                    Text(problem.title).font(.headline)
                    Text(problem.detail).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .card(tint: problem.color)
            .transition(.blurReplace)
        }
    }

    private var problem: (title: String, detail: String, symbol: String, color: Color)? {
        if let errorMessage {
            return ("Can't reach the checker", errorMessage, "wifi.exclamationmark", .orange)
        }
        switch status.state {
        case .live, .paused:
            return nil
        case let .failing(error):
            return ("Apple isn't answering", error ?? "The last \(status.health.consecutiveFailures) checks failed.", "exclamationmark.triangle.fill", .orange)
        case .stale:
            return ("Results may be stale", "Apple is answering, but even iPhone 17 shows no stock nearby.", "questionmark.circle.fill", .yellow)
        case let .slowed(reason):
            return ("Slowed to every \(status.pace.intervalSeconds)s", "\(reason). Speeds back up after 30 minutes without pushback.", "tortoise.fill", .yellow)
        }
    }
}

// MARK: - Stock

/// What's in stock right now, or failing that when an iPhone Duo was last seen in a store.
struct StockCard: View {
    let status: Status
    let finish: Finish

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            VStack(alignment: .leading, spacing: 16) {
                if status.sightings.isEmpty {
                    lastSeen
                } else {
                    inStock
                }
                if !status.lastSeen.isEmpty {
                    Divider()
                    history
                }
            }
        }
        .card(tint: status.sightings.isEmpty ? nil : .green)
        .sensoryFeedback(.success, trigger: status.sightings.count) { old, new in new > old }
        .animation(.smooth, value: status.sightings)
    }

    private var inStock: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                LiveDot(color: .green, trigger: status.sightings.count, size: 9)
                Text("In stock now").font(.footnote.weight(.semibold)).textCase(.uppercase).tracking(0.6)
            }
            .foregroundStyle(.green)

            ForEach(Dictionary(grouping: status.sightings, by: \.part).sorted { $0.key < $1.key }, id: \.key) { part, sightings in
                let phone = status.model(for: part)
                HStack(alignment: .center, spacing: 12) {
                    Circle().fill((phone?.finish ?? finish).swatch.gradient).frame(width: 22, height: 22)
                        .overlay(Circle().strokeBorder(.primary.opacity(0.2), lineWidth: 1))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(phone?.name ?? part).font(.headline)
                        Text(stores(sightings)).font(.subheadline).foregroundStyle(.secondary)
                        Text(sightings[0].quote).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    if let url = phone?.url {
                        Link("Buy", destination: url)
                            .buttonStyle(.glassProminent)
                            .tint(.green)
                            .font(.headline)
                    }
                }
            }
        }
    }

    private func stores(_ sightings: [Status.Sighting]) -> String {
        let names = sightings.map(\.storeName)
        return names.count <= 3 ? names.formatted(.list(type: .and)) : "\(names.prefix(2).joined(separator: ", ")) and \(names.count - 2) more"
    }

    @ViewBuilder private var lastSeen: some View {
        let latest = status.lastSeen.first { status.model(for: $0.part)?.finish == finish }
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Last seen in \(finish.name)", symbol: "eye")
            if let latest {
                Text(latest.to, format: .relative(presentation: .named))
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                    .contentTransition(.numericText())
                Text("\(status.model(for: latest.part)?.capacity ?? latest.part) at \(place(latest)) · \(lasted(latest))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Text("Not yet")
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                Group {
                    if let since = status.trackingSince {
                        Text("No \(finish.name) iPhone Duo at these \(status.storeCount) stores since tracking began \(since, format: .relative(presentation: .named)).")
                    } else {
                        Text("No \(finish.name) iPhone Duo at these \(status.storeCount) stores yet.")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
        .id(finish)
        .transition(.blurReplace)
    }

    private var history: some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Recent sightings", symbol: "clock.arrow.circlepath")
            ForEach(status.lastSeen.prefix(4), id: \.self) { episode in
                let phone = status.model(for: episode.part)
                HStack(spacing: 12) {
                    Circle().fill((phone?.finish ?? .nightSky).swatch.gradient).frame(width: 14, height: 14)
                        .overlay(Circle().strokeBorder(.primary.opacity(0.2), lineWidth: 1))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(phone?.name ?? episode.part).font(.subheadline.weight(.semibold))
                        Text(place(episode)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(episode.to, format: .relative(presentation: .named)).font(.caption.weight(.medium))
                        Text(lasted(episode)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func place(_ episode: Status.Episode) -> String {
        guard let area = status.area(of: episode.storeNumber), area.name != episode.storeName else { return episode.storeName }
        return "\(episode.storeName), \(area.name)"
    }

    private func lasted(_ episode: Status.Episode) -> String {
        let seconds = episode.to.timeIntervalSince(episode.from)
        guard seconds >= 60 else { return "briefly" }
        return "for " + Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes], width: .wide, maximumUnitCount: 2))
    }
}

// MARK: - Watching

/// Which capacities of the selected colour the Worker checks. Tapping one turns it on or off.
struct WatchingCard: View {
    let model: StatusModel
    let status: Status
    let finish: Finish

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CardHeader(title: "Alerts for \(finish.name)", symbol: "bell.badge")
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    ForEach(status.models(in: finish)) { phone in
                        chip(phone)
                    }
                }
            }
            .disabled(model.isWorking)
            Text(footer).font(.caption).foregroundStyle(.secondary)
        }
        .card()
    }

    private func chip(_ phone: Status.Model) -> some View {
        let watching = status.config.parts.contains(phone.part)
        let inStock = status.sightings.contains { $0.part == phone.part }
        return Button {
            Task { await model.setWatching(phone.part, !watching) }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: watching ? "checkmark.circle.fill" : "circle")
                    .font(.body)
                    .foregroundStyle(watching ? finish.accent : .secondary)
                    .contentTransition(.symbolEffect(.replace))
                Text(phone.capacity).font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(watching ? .primary : .secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .overlay(alignment: .topTrailing) {
                if inStock { Circle().fill(.green).frame(width: 8, height: 8).padding(8) }
            }
            .contentShape(.rect(cornerRadius: 18))
        }
        .buttonStyle(.plain)
        .glassEffect(watching ? .regular.tint(finish.accent.opacity(0.35)).interactive() : .regular.interactive(), in: .rect(cornerRadius: 18))
        .sensoryFeedback(.impact(weight: .light), trigger: watching)
        .accessibilityLabel("\(phone.name), \(watching ? "alerts on" : "alerts off")")
    }

    private var footer: String {
        let watched = status.models(in: finish).filter { status.config.parts.contains($0.part) }
        guard !watched.isEmpty else { return "No alerts for \(finish.name). Tap a size to watch it." }
        return "Each checked in every area every \(status.pace.intervalSeconds)s."
    }
}

// MARK: - Activity

/// The Worker's requests to Apple as they happen.
struct ActivityCard: View {
    let model: StatusModel
    let status: Status
    let finish: Finish

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    CardHeader(title: "Live activity", symbol: "waveform.path.ecg")
                    LiveDot(color: status.state.color, trigger: model.pulses, size: 7)
                }
                VStack(spacing: 10) {
                    ForEach(model.liveChecks.prefix(5)) { check in
                        row(check, now: context.date)
                            .transition(.asymmetric(insertion: .push(from: .top), removal: .identity))
                    }
                }
                .animation(.snappy, value: model.liveChecks.first?.id)
            }
        }
        .card()
    }

    private func row(_ check: Status.Check, now: Date) -> some View {
        HStack(spacing: 12) {
            Image(systemName: check.ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                .foregroundStyle(check.ok ? finish.accent : .orange)
            VStack(alignment: .leading, spacing: 1) {
                Text(check.area).font(.subheadline.weight(.semibold))
                Text(status.model(for: check.part)?.name ?? check.part).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text(check.ok ? "\(check.ms) ms" : "failed").font(.caption.weight(.medium)).monospacedDigit()
                Text("\(Int(max(0, now.timeIntervalSince(check.at))))s ago").font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        }
    }
}

// MARK: - Stores

struct Pin: Identifiable {
    let store: Status.Store
    let area: String
    let coordinate: CLLocationCoordinate2D
    var id: String { store.number }
}

extension Status {
    var pins: [Pin] {
        areas.flatMap { area in
            area.stores.compactMap { store in
                store.coordinate.map { Pin(store: store, area: area.name, coordinate: $0) }
            }
        }
    }

    func inStock(at store: String) -> [Sighting] {
        sightings.filter { $0.storeNumber == store }
    }
}

/// A store on the map. The pins in an area ripple each time the Worker asks Apple about it.
struct StorePin: View {
    let pin: Pin
    let status: Status
    let color: Color
    let pulse: Int
    var selected = false

    var body: some View {
        let tint = !pin.store.covered ? Color.gray : status.inStock(at: pin.store.number).isEmpty ? color : .green
        Circle()
            .fill(tint)
            .frame(width: 12, height: 12)
            .overlay(Circle().strokeBorder(.white, lineWidth: 2))
            .background { Ripple(color: tint, trigger: pulse, reach: 3, duration: 1.4) }
            .scaleEffect(selected ? 1.6 : 1)
            .shadow(color: .black.opacity(0.3), radius: 2, y: 1)
            .animation(.bouncy, value: selected)
    }
}

struct StoresCard: View {
    let model: StatusModel
    let status: Status
    let finish: Finish
    let open: (String?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            CardHeader(title: "Stores", symbol: "mappin.and.ellipse", trailing: "\(status.storeCount)")

            Button { open(nil) } label: {
                Map(initialPosition: .automatic, interactionModes: []) {
                    ForEach(status.pins) { pin in
                        Annotation(pin.store.name, coordinate: pin.coordinate) {
                            StorePin(pin: pin, status: status, color: finish.accent, pulse: model.areaPulses[pin.area] ?? 0)
                        }
                        .annotationTitles(.hidden)
                    }
                }
                .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll))
                .allowsHitTesting(false)
                .frame(height: 220)
                .clipShape(.rect(cornerRadius: 18))
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.footnote.weight(.semibold))
                        .padding(10)
                        .glassEffect(.regular, in: .circle)
                        .padding(10)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open the store map")

            VStack(spacing: 0) {
                ForEach(status.areas) { area in
                    Button { open(area.id) } label: { row(area) }
                        .buttonStyle(.plain)
                    if area.id != status.areas.last?.id { Divider().padding(.leading, 34) }
                }
            }
        }
        .card()
    }

    private func row(_ area: Status.Area) -> some View {
        let missing = area.stores.filter { !$0.covered }.count
        let inStock = area.stores.reduce(0) { $0 + (status.inStock(at: $1.number).isEmpty ? 0 : 1) }
        return HStack(spacing: 12) {
            LiveDot(color: inStock > 0 ? .green : finish.accent, trigger: model.areaPulses[area.name] ?? 0, size: 8)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(area.name).font(.subheadline.weight(.semibold))
                Text(area.stores.map(\.name).formatted(.list(type: .and)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if missing > 0 {
                Label("\(missing)", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.yellow)
            }
            Text(inStock > 0 ? "\(inStock) in stock" : "\(area.stores.count)")
                .font(.subheadline.weight(inStock > 0 ? .semibold : .regular))
                .foregroundStyle(inStock > 0 ? .green : .secondary)
                .monospacedDigit()
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 10)
        .contentShape(.rect)
    }
}
