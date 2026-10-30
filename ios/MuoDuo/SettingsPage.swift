import AlarmKit
import SwiftUI
import UIKit

/// Pushed from the home screen, in the same glass over the same sky. Alerts come first: they're what
/// matters most, and turning one off is the easiest way to miss stock. Alerts, the alarm, areas and mute
/// are this iPhone's own; pausing is for everyone, so only the owner's key can do it.
struct SettingsPage: View {
    let model: StatusModel
    let finish: Finish

    var body: some View {
        ScrollView {
            if let status = model.status {
                VStack(spacing: 16) {
                    if let device = status.device {
                        AlertsCard(model: model, status: status, device: device)
                        AlarmCard(model: model, status: status, device: device)
                    } else {
                        UnregisteredCard(model: model)
                    }
                    CheckingCard(model: model, status: status)
                    NotificationsCard(model: model, status: status)
                    AreasCard(model: model, status: status)
                    Text("Product images are Apple's, loaded from apple.com.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .readableWidth()
                .animation(.smooth, value: status.device)
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
    let device: Status.Device

    var body: some View {
        let watching = status.models.filter { device.parts.contains($0.part) }.count
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
                                AlertChip(model: model, status: status, device: device, phone: phone, finish: finish)
                            }
                        }
                    }
                }
            }
            .disabled(model.isWorking)
            if let warning {
                Label(warning, systemImage: "bell.slash.fill")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.orange)
            } else {
                Text("Every size you watch is checked in \(areas), every \(status.pace.intervalSeconds)s.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .card()
    }

    private var warning: String? {
        if device.muted { return "This iPhone is muted, so it won't hear about any stock." }
        if device.parts.isEmpty { return "No alerts are on, so you won't hear about any stock." }
        if device.areas.isEmpty { return "No areas are on, so you won't hear about any stock." }
        return nil
    }

    private var areas: String {
        let count = device.areas.count
        if count == 1 { return "your area" }
        return count == status.areas.count ? "all \(count) areas" : "your \(count) areas"
    }
}

private struct AlertChip: View {
    let model: StatusModel
    let status: Status
    let device: Status.Device
    let phone: Status.Model
    let finish: Finish

    @State private var confirmingOff = false

    var body: some View {
        let watching = device.parts.contains(phone.part)
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
            Text("This iPhone won't hear when it's in stock at any of its \(status.storeCount(in: device.areas)) stores.")
        }
        .accessibilityLabel("\(phone.name), \(watching ? "alerts on" : "alerts off")")
    }
}

// MARK: - Alarm

/// Stock found in the alarm hours rings like an alarm clock, through silent mode and Focus. Tapping the hours
/// opens wheels to choose them, saved once they've stopped turning. The test is sent after a delay, so the
/// phone can be locked to see it as it would be.
private struct AlarmCard: View {
    let model: StatusModel
    let status: Status
    let device: Status.Device

    @State private var permission = AlarmManager.shared.authorizationState
    @State private var choosingHours = false
    /// The hours on the wheels, from when they were opened. Kept after they close, so a change made just
    /// before closing still saves.
    @State private var draft: Status.Alarm?
    @State private var testResult: String?
    @State private var sendingTest = false

    private static let testDelay = 10

    var body: some View {
        let alarm = device.alarm
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Alarm", symbol: "alarm", trailing: alarm.enabled ? alarm.hours : "Off")
            VStack(spacing: 0) {
                Toggle("Ring for stock", isOn: Binding(
                    get: { alarm.enabled },
                    set: { on in Task { await model.setAlarm(on) } }
                ))
                .disabled(model.isWorking)
                .padding(.vertical, 7)
                Divider()
                hoursRow(alarm)
                if choosingHours {
                    hourWheels(alarm)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                Divider()
                Row("Alarms on this iPhone") { permissionStatus }
            }
            switch permission {
            case .notDetermined:
                Button {
                    Task { permission = (try? await AlarmManager.shared.requestAuthorization()) ?? permission }
                } label: {
                    Label("Allow Alarms", systemImage: "alarm.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .padding(.top, 6)
            case .denied:
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                } label: {
                    Label("Alarms are off. Turn them on", systemImage: "alarm.waves.left.and.right.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .tint(.orange)
                .controlSize(.large)
                .padding(.top, 6)
            default:
                EmptyView()
            }
            Button {
                sendingTest = true
                Task {
                    let result = await model.sendTestAlarm(in: Self.testDelay)
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
                        Image(systemName: "alarm")
                    }
                    Text("Test Alarm in \(Self.testDelay)s")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .disabled(sendingTest || permission != .authorized)
            .padding(.top, 6)
            Text(testResult ?? "Stock found in these hours rings an alarm, even on silent. At other times it's a notification.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .contentTransition(.opacity)
        }
        .card()
        .task {
            for await state in AlarmManager.shared.authorizationUpdates { permission = state }
        }
        // Each turn of a wheel restarts this, so only where they stop is saved.
        .task(id: draft) {
            guard let draft, draft.from != device.alarm.from || draft.to != device.alarm.to else { return }
            do { try await Task.sleep(for: .seconds(0.6)) } catch { return }
            await model.setAlarmHours(from: draft.from, to: draft.to)
        }
    }

    private func hoursRow(_ alarm: Status.Alarm) -> some View {
        let shown = choosingHours ? draft ?? alarm : alarm
        return Button {
            if !choosingHours { draft = alarm }
            withAnimation(.smooth) { choosingHours.toggle() }
        } label: {
            Row("Hours") {
                Text(shown.allDay ? shown.hours : "\(shown.hours) UK")
                    .foregroundStyle(choosingHours ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .contentTransition(.numericText())
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityHint(choosingHours ? "Hides the hours" : "Shows the hours to choose from")
    }

    /// From and to, in whole UK hours. Moving one past the other carries the other along, keeping the length.
    private func hourWheels(_ alarm: Status.Alarm) -> some View {
        let hours = draft ?? alarm
        let length = hours.to - hours.from
        return HStack(spacing: 0) {
            HourWheel(title: "From", hours: 0...23, selection: Binding(
                get: { hours.from },
                set: { from in
                    var next = hours
                    next.from = from
                    if next.to <= from { next.to = min(24, from + length) }
                    draft = next
                }
            ))
            HourWheel(title: "To", hours: 1...24, selection: Binding(
                get: { hours.to },
                set: { to in
                    var next = hours
                    next.to = to
                    if next.from >= to { next.from = max(0, to - length) }
                    draft = next
                }
            ))
        }
        .padding(.bottom, 6)
    }

    @ViewBuilder private var permissionStatus: some View {
        switch permission {
        case .authorized:
            Label("Allowed", systemImage: "checkmark.circle.fill").labelStyle(TrailingIcon(color: .green))
        case .denied:
            Label("Off", systemImage: "exclamationmark.triangle.fill").labelStyle(TrailingIcon(color: .orange))
        default:
            Text("Not asked yet")
        }
    }
}

private struct HourWheel: View {
    let title: String
    let hours: ClosedRange<Int>
    @Binding var selection: Int

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Picker(title, selection: $selection) {
                ForEach(hours, id: \.self) { Text(Status.Alarm.clock($0)).monospacedDigit().tag($0) }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            .frame(height: 130)
        }
        .frame(maxWidth: .infinity)
        // Side by side, each wheel would otherwise take touches meant for the other.
        .clipped()
        .contentShape(.rect)
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
                if status.isOwner {
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
                        Text("No phone will get alerts until you turn it back on.")
                    }
                } else {
                    Row("Checking Apple for stock", value: status.config.paused ? "Paused" : "On")
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
    @State private var confirmingMute = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Notifications", symbol: "iphone.radiowaves.left.and.right")
            VStack(spacing: 0) {
                Row("This iPhone") { registration }
                if let device = status.device {
                    Divider()
                    Toggle("Mute this iPhone", isOn: Binding(
                        get: { device.muted },
                        set: { muted in
                            if muted {
                                confirmingMute = true
                            } else {
                                Task { await model.setMuted(false) }
                            }
                        }
                    ))
                    .disabled(model.isWorking)
                    .padding(.vertical, 7)
                    .confirmationDialog("Mute this iPhone?", isPresented: $confirmingMute, titleVisibility: .visible) {
                        Button("Mute", role: .destructive) {
                            Task { await model.setMuted(true) }
                        }
                    } message: {
                        Text("It won't get stock alerts or warnings until you turn this off. Other phones still will.")
                    }
                }
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

// MARK: - Unregistered

/// Alerts and the alarm are this iPhone's own, so they wait until it has registered.
private struct UnregisteredCard: View {
    let model: StatusModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Alerts", symbol: "bell.badge")
            switch model.registration {
            case .pending, .registered:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Registering this iPhone…")
                }
                .padding(.vertical, 7)
            case let .failed(message):
                Label("Couldn't register: \(message)", systemImage: "exclamationmark.triangle.fill")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.orange)
                    .padding(.vertical, 7)
            }
            Text("Each phone chooses its own models, areas and alarm hours once it's registered for notifications.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .card()
    }
}

// MARK: - Areas

/// Each search the Worker runs, opening up to the stores it watches. The tick is whether this iPhone
/// hears about stock there.
private struct AreasCard: View {
    let model: StatusModel
    let status: Status

    @State private var expanded: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Areas", symbol: "mappin.and.ellipse", trailing: "\(status.storeCount) stores")
            VStack(spacing: 0) {
                ForEach(status.areas) { area in
                    if area.id != status.areas.first?.id { Divider() }
                    HStack(spacing: 12) {
                        if let device = status.device {
                            AreaTick(model: model, status: status, area: area, on: device.areas.contains(area.search))
                        }
                        areaRow(area)
                    }
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
            Text(caption)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .card()
        .sensoryFeedback(.selection, trigger: expanded)
    }

    private var caption: String {
        let ticks = status.device == nil ? "" : "Tick the areas this iPhone hears about. "
        let changing = status.isOwner ? " Change them with PUT /config on the Worker." : ""
        return "\(ticks)Each area is one search on Apple.\(changing)"
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

/// Turning an area on is a tap; turning one off asks first, as for a model.
private struct AreaTick: View {
    let model: StatusModel
    let status: Status
    let area: Status.Area
    let on: Bool

    @State private var confirmingOff = false

    var body: some View {
        Button {
            if on {
                confirmingOff = true
            } else {
                Task { await model.setArea(area.search, true) }
            }
        } label: {
            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .contentTransition(.symbolEffect(.replace))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(model.isWorking)
        .sensoryFeedback(.impact(weight: .light), trigger: on)
        .confirmationDialog("Turn off alerts in \(area.name)?", isPresented: $confirmingOff, titleVisibility: .visible) {
            Button("Turn Off Alerts", role: .destructive) {
                Task { await model.setArea(area.search, false) }
            }
        } message: {
            Text("This iPhone won't hear about stock at any of its \(area.stores.count) stores.")
        }
        .accessibilityLabel("\(area.name), \(on ? "alerts on" : "alerts off")")
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
