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

/// Only shown when the checker can't be trusted, so the home screen stays calm when it can.
struct HealthBanner: View {
    let status: Status
    let errorMessage: String?

    var body: some View {
        if let errorMessage {
            Notice(title: "Can't reach the checker", detail: errorMessage, symbol: "wifi.exclamationmark", color: .orange)
        } else if case let .failing(error) = status.state {
            Notice(title: "Apple isn't answering", detail: error ?? "The last \(status.health.consecutiveFailures) checks failed.", symbol: "exclamationmark.triangle.fill", color: .orange)
        } else if status.state == .stale {
            Notice(title: "Results may be stale", detail: "Apple is answering, but even iPhone 17 shows no stock nearby.", symbol: "questionmark.circle.fill", color: .yellow)
        }
    }
}

/// Apple pushed back, so checks are slower for a couple of minutes. Worth knowing but not urgent, so it
/// sits at the bottom of the home screen.
struct SlowedBanner: View {
    let status: Status

    var body: some View {
        if case let .slowed(reason) = status.state {
            Notice(title: "Slowed to every \(status.pace.intervalSeconds)s", detail: "\(reason). Speeds up again after 2 minutes without pushback.", symbol: "tortoise.fill", color: .yellow)
        }
    }
}

private struct Notice: View {
    let title: String
    let detail: String
    let symbol: String
    let color: Color

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(color)
                .symbolEffect(.pulse, options: .repeating)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .card(tint: color)
        .transition(.blurReplace)
    }
}

// MARK: - In stock

/// Straight under the colour picker the moment anything is in stock: which size and colour, at which
/// stores, and one tap to buy it in the Apple Store app.
struct InStockCard: View {
    let model: StatusModel
    let status: Status
    let finish: Finish

    var body: some View {
        if !status.sightings.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 8) {
                    LiveDot(color: .green, trigger: model.pulses, size: 9)
                    Text("In stock now").font(.footnote.weight(.bold)).textCase(.uppercase).tracking(0.8)
                    Spacer()
                    Text(storeCount).font(.footnote.weight(.semibold))
                }
                .foregroundStyle(.green)

                ForEach(Array(groups.enumerated()), id: \.element.part) { index, group in
                    if index > 0 { Divider() }
                    if let phone = status.model(for: group.part) {
                        InStockModel(model: model, status: status, phone: phone, sightings: group.sightings)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular.tint(.green.opacity(0.3)), in: .rect(cornerRadius: 32))
            .transition(.blurReplace.combined(with: .scale(0.96)))
        }
    }

    /// One group per model: the selected colour first, then in catalogue order.
    private var groups: [(part: String, sightings: [Status.Sighting])] {
        let order = status.models.map(\.part)
        func rank(_ part: String) -> (Int, Int) {
            (status.model(for: part)?.finish == finish ? 0 : 1, order.firstIndex(of: part) ?? .max)
        }
        return Dictionary(grouping: status.sightings, by: \.part)
            .map { (part: $0.key, sightings: $0.value) }
            .sorted { rank($0.part) < rank($1.part) }
    }

    private var storeCount: String {
        let stores = Set(status.sightings.map(\.storeNumber)).count
        return stores == 1 ? "1 store" : "\(stores) stores"
    }
}

/// One size and colour that's in stock: the phone, where it is, and a Buy button.
private struct InStockModel: View {
    let model: StatusModel
    let status: Status
    let phone: Status.Model
    let sightings: [Status.Sighting]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(phone.capacity)
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                    HStack(spacing: 8) {
                        Circle()
                            .fill((phone.finish ?? .nightSky).swatch.gradient)
                            .frame(width: 16, height: 16)
                            .overlay(Circle().strokeBorder(.primary.opacity(0.25), lineWidth: 1))
                        Text(phone.colour).font(.headline)
                    }
                }
                Spacer(minLength: 0)
                thumbnail
            }

            VStack(spacing: 12) {
                ForEach(sightings, id: \.storeNumber) { sighting in
                    store(sighting)
                }
            }

            VStack(spacing: 8) {
                Button {
                    Task { await AppleStore.open(phone.url) }
                } label: {
                    Label("Buy in Apple Store", systemImage: "bag.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .tint(.green)
                .controlSize(.large)
                .accessibilityHint("Opens \(phone.name) in the Apple Store app")

                Text(sightings.count == 1 ? "Choose Apple \(sightings[0].storeName) for pickup at checkout." : "Choose one of these stores for pickup at checkout.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    /// Apple's shot of the phone in this colour. Like the hero, only the middle, where the phone is, is laid out.
    @ViewBuilder private var thumbnail: some View {
        if let image = phone.finish.flatMap(model.image(for:)) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: 128, height: 128 * 1112 / 940)
                .frame(width: 128, height: 80)
                .accessibilityHidden(true)
        }
    }

    private func store(_ sighting: Status.Sighting) -> some View {
        let episode = status.lastSeen.first { $0.part == sighting.part && $0.storeNumber == sighting.storeNumber }
        return HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "mappin.circle.fill")
                .font(.title3)
                .foregroundStyle(.green)
            VStack(alignment: .leading, spacing: 2) {
                Text("Apple \(sighting.storeName)").font(.headline)
                Text(place(sighting)).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(sighting.quote).font(.subheadline.weight(.semibold)).foregroundStyle(.green)
                if let episode {
                    Text("for \(Text(episode.from, style: .timer))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func place(_ sighting: Status.Sighting) -> String {
        let area = status.area(of: sighting.storeNumber)
        let city = area?.stores.first { $0.number == sighting.storeNumber }?.city
        return [city, area?.name].compactMap { $0 }.uniqued().joined(separator: " · ")
    }
}

// MARK: - Last seen

/// When an iPhone Duo in the selected colour was last in stock, and the most recent sightings of any.
struct LastSeenCard: View {
    let status: Status
    let finish: Finish

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            VStack(alignment: .leading, spacing: 16) {
                lastSeen
                if !status.lastSeen.isEmpty {
                    Divider()
                    history
                }
            }
        }
        .card()
        .animation(.smooth, value: status.lastSeen)
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
