import SwiftUI
import UIKit

/// Pushed from the home screen, in the same glass over the same sky. Alerts come first: they're what
/// matters most, and turning one off is the easiest way to miss stock.
struct SettingsPage: View {
    let model: StatusModel
    let finish: Finish

    var body: some View {
        ScrollView {
            if let status = model.status {
                VStack(spacing: 16) {
                    AlertsCard(model: model, status: status)
                    CheckingCard(model: model, status: status)
                    NotificationsCard(model: model, status: status)
                    AreasCard(status: status)
                    Text("Product images are Apple's, loaded from apple.com.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .animation(.smooth, value: status.config.parts)
                .animation(.smooth, value: status.config.paused)
            }
        }
        .scrollIndicators(.hidden)
        .background { Backdrop(finish: finish) }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
    }
}

// MARK: - Alerts

/// Every size of both colours. Turning one on is a tap; turning one off asks first.
private struct AlertsCard: View {
    let model: StatusModel
    let status: Status

    var body: some View {
        let watching = status.models.filter { status.config.parts.contains($0.part) }.count
        VStack(alignment: .leading, spacing: 18) {
            CardHeader(title: "Alerts", symbol: "bell.badge", trailing: "\(watching) on")
            ForEach(Finish.allCases) { finish in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(finish.swatch.gradient)
                            .frame(width: 16, height: 16)
                            .overlay(Circle().strokeBorder(.primary.opacity(0.25), lineWidth: 1))
                        Text(finish.name).font(.subheadline.weight(.semibold))
                    }
                    GlassEffectContainer(spacing: 8) {
                        HStack(spacing: 8) {
                            ForEach(status.models(in: finish)) { phone in
                                AlertChip(model: model, status: status, phone: phone, finish: finish)
                            }
                        }
                    }
                }
            }
            .disabled(model.isWorking)
            if watching == 0 {
                Label("No alerts are on, so you won't hear about any stock.", systemImage: "bell.slash.fill")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.orange)
            } else {
                Text("Every size you watch is checked in all \(status.areas.count) areas, every \(status.pace.intervalSeconds)s.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .card()
    }
}

private struct AlertChip: View {
    let model: StatusModel
    let status: Status
    let phone: Status.Model
    let finish: Finish

    @State private var confirmingOff = false

    var body: some View {
        let watching = status.config.parts.contains(phone.part)
        let inStock = status.sightings.contains { $0.part == phone.part }
        Button {
            if watching {
                confirmingOff = true
            } else {
                Task { await model.setWatching(phone.part, true) }
            }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: watching ? "checkmark.circle.fill" : "circle")
                    .font(.body)
                    // A white tick on the accent, which would otherwise sink into glass tinted the same colour.
                    .foregroundStyle(watching ? AnyShapeStyle(.white) : AnyShapeStyle(.secondary), watching ? AnyShapeStyle(finish.accent) : AnyShapeStyle(.secondary))
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
        .confirmationDialog("Turn off alerts for \(phone.name)?", isPresented: $confirmingOff, titleVisibility: .visible) {
            Button("Turn Off Alerts", role: .destructive) {
                Task { await model.setWatching(phone.part, false) }
            }
        } message: {
            Text("You won't hear when it's in stock at any of the \(status.storeCount) stores.")
        }
        .accessibilityLabel("\(phone.name), \(watching ? "alerts on" : "alerts off")")
    }
}

// MARK: - Checking

private struct CheckingCard: View {
    let model: StatusModel
    let status: Status

    @State private var confirmingPause = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Checking", symbol: "dot.radiowaves.left.and.right")
            VStack(spacing: 0) {
                Toggle("Check Apple for stock", isOn: Binding(
                    get: { !status.config.paused },
                    set: { checking in
                        if checking {
                            Task { await model.setPaused(false) }
                        } else {
                            confirmingPause = true
                        }
                    }
                ))
                .disabled(model.isWorking)
                .padding(.vertical, 7)
                .confirmationDialog("Pause checking?", isPresented: $confirmingPause, titleVisibility: .visible) {
                    Button("Pause", role: .destructive) {
                        Task { await model.setPaused(true) }
                    }
                } message: {
                    Text("No alerts will arrive until you turn it back on.")
                }
                Divider()
                Row("Each size, each area", value: "every \(status.pace.intervalSeconds)s")
                if let date = status.health.lastSuccessAt {
                    Divider()
                    Row("Last good check") { Text(date, format: .relative(presentation: .named)) }
                }
                if let error = status.health.lastError {
                    Divider()
                    Row("Last error") { Text(error) }
                }
            }
            if let reason = status.pace.reason {
                Text("Slowed down: \(reason).").font(.caption).foregroundStyle(.secondary)
            }
        }
        .card()
    }
}

// MARK: - Notifications

private struct NotificationsCard: View {
    let model: StatusModel
    let status: Status

    @State private var testResult: String?
    @State private var sendingTest = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Notifications", symbol: "iphone.radiowaves.left.and.right")
            VStack(spacing: 0) {
                Row("This iPhone") { registration }
                Divider()
                Row("Devices registered", value: "\(status.devices)")
            }
            if model.notificationsAllowed == false {
                Button {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label("Notifications are off. Turn them on", systemImage: "bell.slash.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .tint(.orange)
                .controlSize(.large)
                .padding(.top, 6)
            }
            Button {
                sendingTest = true
                Task {
                    let result = await model.sendTest()
                    withAnimation(.smooth) {
                        testResult = result
                        sendingTest = false
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    if sendingTest {
                        ProgressView()
                    } else {
                        Image(systemName: "paperplane.fill")
                    }
                    Text("Send Test Notification")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .disabled(sendingTest)
            .padding(.top, 6)
            if let testResult {
                Text(testResult)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .transition(.opacity)
            }
        }
        .card()
    }

    @ViewBuilder private var registration: some View {
        switch model.registration {
        case .pending:
            Text("Registering…")
        case .registered:
            Label("Registered (\(AppConfig.apnsEnvironment))", systemImage: "checkmark.circle.fill")
                .labelStyle(TrailingIcon(color: .green))
        case let .failed(message):
            Label("Failed: \(message)", systemImage: "exclamationmark.triangle.fill")
                .labelStyle(TrailingIcon(color: .orange))
        }
    }
}

/// Status text with its glyph after it, so the text lines up with the other values.
private struct TrailingIcon: LabelStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            configuration.title
            configuration.icon.foregroundStyle(color).imageScale(.small)
        }
    }
}

// MARK: - Areas

/// Each search the Worker runs, opening up to the stores it watches.
private struct AreasCard: View {
    let status: Status

    @State private var expanded: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Areas", symbol: "mappin.and.ellipse", trailing: "\(status.storeCount) stores")
            VStack(spacing: 0) {
                ForEach(status.areas) { area in
                    if area.id != status.areas.first?.id { Divider() }
                    areaRow(area)
                    if expanded.contains(area.id) {
                        VStack(spacing: 0) {
                            ForEach(area.stores) { store in
                                storeRow(store)
                            }
                        }
                        .padding(.bottom, 8)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
            }
            .clipped()
            Text("Each area is one search on Apple. Change them with PUT /config on the Worker.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .card()
        .sensoryFeedback(.selection, trigger: expanded)
    }

    private func areaRow(_ area: Status.Area) -> some View {
        let open = expanded.contains(area.id)
        let uncovered = area.stores.filter { !$0.covered }.count
        return Button {
            withAnimation(.smooth) {
                if open { expanded.remove(area.id) } else { expanded.insert(area.id) }
            }
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(area.name)
                    Text("Near \(area.search)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if uncovered > 0 {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                        .accessibilityLabel("\(uncovered) not covered")
                }
                Text("\(area.stores.count)").foregroundStyle(.secondary).monospacedDigit()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(open ? 90 : 0))
            }
            .padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityValue(open ? "Expanded" : "Collapsed")
    }

    private func storeRow(_ store: Status.Store) -> some View {
        HStack {
            Text(store.name).font(.subheadline)
            Spacer()
            if store.covered {
                Text(store.number).font(.caption.monospaced()).foregroundStyle(.tertiary)
            } else {
                Label("Not covered", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.yellow)
            }
        }
        .padding(.vertical, 6)
        .padding(.leading, 12)
    }
}

// MARK: - Rows

/// A setting and its value, for the rows inside a card.
private struct Row<Value: View>: View {
    let title: String
    @ViewBuilder var value: Value

    init(_ title: String, @ViewBuilder value: () -> Value) {
        self.title = title
        self.value = value()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
            Spacer(minLength: 16)
            value
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }
}

private extension Row where Value == Text {
    init(_ title: String, value: String) {
        self.init(title) { Text(value) }
    }
}
