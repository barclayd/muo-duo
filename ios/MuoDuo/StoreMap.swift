import MapKit
import SwiftUI

/// Every watched store. Area chips fly the camera between London, the south and Manchester.
struct StoreMap: View {
    let model: StatusModel
    let finish: Finish
    let chrome: PageChrome
    let close: () -> Void

    @State private var focus: String?
    /// Opened from a sighting: the stores with stock, until an area is picked.
    @State private var stock: Bool
    @State private var position: MapCameraPosition = .automatic
    @State private var selected: String?
    @Namespace private var chips

    init(model: StatusModel, finish: Finish, focus: MapFocus, chrome: PageChrome, close: @escaping () -> Void) {
        self.model = model
        self.finish = finish
        self.chrome = chrome
        self.close = close
        _focus = State(initialValue: focus.area)
        _stock = State(initialValue: focus.store != nil)
        _selected = State(initialValue: focus.store)
    }

    var body: some View {
        if let status = model.status {
            Map(position: $position, selection: $selected) {
                ForEach(status.pins) { pin in
                    Annotation(pin.store.name, coordinate: pin.coordinate) {
                        StorePin(pin: pin, status: status, color: finish.accent, pulse: model.areaPulses[pin.area] ?? 0, selected: selected == pin.id)
                    }
                    .tag(pin.id)
                }
            }
            .mapStyle(.standard(elevation: .realistic, emphasis: .muted, pointsOfInterest: .excludingAll))
            .mapControls { MapCompass() }
            .ignoresSafeArea()
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 12) {
                    if let pin = status.pins.first(where: { $0.id == selected }) {
                        // On the open iPhone Duo the map is wide; the card stays phone-sized at the leading edge.
                        StoreDetail(pin: pin, status: status, finish: finish)
                            .frame(maxWidth: 420)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    areaChips(status)
                }
                .padding(.bottom, 8)
                .animation(.smooth, value: selected)
            }
            .pageChrome(chrome, title: title(status), close: close)
            .onAppear { fly(to: focus, stock: stock, in: status, animated: false) }
            .sensoryFeedback(.selection, trigger: selected)
        }
    }

    private func title(_ status: Status) -> String {
        let stocked = Set(status.sightings.map(\.storeNumber)).count
        if stock, stocked > 0 { return stocked == 1 ? "1 store with stock" : "\(stocked) stores with stock" }
        return focus.flatMap { id in status.areas.first { $0.id == id }?.name } ?? "\(status.storeCount) Apple Stores"
    }

    private func areaChips(_ status: Status) -> some View {
        ScrollView(.horizontal) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    chip("All", id: nil, status: status)
                    ForEach(status.areas) { area in
                        chip(area.name, id: area.id, status: status)
                    }
                }
            }
        }
        .contentMargins(.horizontal, 16, for: .scrollContent)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }

    private func chip(_ name: String, id: String?, status: Status) -> some View {
        let selected = !stock && focus == id
        return Button {
            withAnimation(.smooth) {
                focus = id
                stock = false
            }
            self.selected = nil
            fly(to: id, in: status, animated: true)
        } label: {
            Text(name)
                .font(.subheadline.weight(selected ? .semibold : .medium))
                .lineLimit(1)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .foregroundStyle(selected ? .primary : .secondary)
        .glassEffect(selected ? .regular.tint(finish.accent.opacity(0.4)).interactive() : .regular.interactive(), in: .capsule)
    }

    private func fly(to area: String?, stock: Bool = false, in status: Status, animated: Bool) {
        let stocked = Set(status.sightings.map(\.storeNumber))
        let coordinates = status.pins.filter { pin in
            stock ? stocked.contains(pin.id) : area.map { status.area(of: pin.id)?.id == $0 } ?? true
        }.map(\.coordinate)
        guard let region = MKCoordinateRegion(fitting: coordinates) else { return }
        if animated {
            withAnimation(.smooth(duration: 1.2)) { position = .region(region) }
        } else {
            position = .region(region)
        }
    }
}

private extension MKCoordinateRegion {
    init?(fitting coordinates: [CLLocationCoordinate2D]) {
        guard let first = coordinates.first else { return nil }
        var minLat = first.latitude, maxLat = first.latitude, minLon = first.longitude, maxLon = first.longitude
        for c in coordinates {
            minLat = min(minLat, c.latitude); maxLat = max(maxLat, c.latitude)
            minLon = min(minLon, c.longitude); maxLon = max(maxLon, c.longitude)
        }
        // Room around the edges for the pins and the glass over the map.
        self.init(
            center: CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2 - (maxLat - minLat) * 0.12, longitude: (minLon + maxLon) / 2),
            span: MKCoordinateSpan(latitudeDelta: max(0.04, (maxLat - minLat) * 1.6), longitudeDelta: max(0.04, (maxLon - minLon) * 1.2))
        )
    }
}

/// The selected store: what's there now, and when stock was last seen there.
private struct StoreDetail: View {
    let pin: Pin
    let status: Status
    let finish: Finish

    var body: some View {
        let now = status.inStock(at: pin.store.number)
        let seen = status.lastSeen.first { $0.storeNumber == pin.store.number }
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Apple \(pin.store.name)").font(.title3.weight(.semibold))
                    Text([pin.store.city, pin.area].compactMap { $0 }.uniqued().joined(separator: " · "))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Text(pin.store.number).font(.caption.monospaced()).foregroundStyle(.tertiary)
            }
            if !pin.store.covered {
                Label("Apple left this store out of its last answer, so it isn't being watched right now.", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.yellow)
            } else if !now.isEmpty {
                ForEach(now, id: \.self) { sighting in
                    let phone = status.model(for: sighting.part)
                    HStack {
                        Label(phone?.name ?? sighting.part, systemImage: "checkmark.seal.fill").font(.subheadline.weight(.semibold)).foregroundStyle(.green)
                        Spacer()
                        if let url = phone?.url {
                            Button("Buy") { Task { await AppleStore.open(url) } }.buttonStyle(.glassProminent).tint(.green)
                        }
                    }
                }
            } else if let seen {
                Label {
                    Text("\(status.model(for: seen.part)?.name ?? seen.part) last seen \(seen.to, format: .relative(presentation: .named))")
                } icon: {
                    Image(systemName: "eye")
                }
                .font(.subheadline).foregroundStyle(.secondary)
            } else {
                Label("No iPhone Duo seen here yet", systemImage: "eye.slash").font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
    }
}

extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
