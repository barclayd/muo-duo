import SwiftUI
import UIKit

struct ContentView: View {
    let model: StatusModel

    @Environment(\.scenePhase) private var scenePhase
    @State private var testResult: String?

    var body: some View {
        NavigationStack {
            Group {
                if let status = model.status {
                    List {
                        if let error = model.errorMessage {
                            Text(error).foregroundStyle(.red)
                        }
                        CheckerSection(status: status, model: model)
                        StockSection(status: status)
                        ModelsSection(status: status, model: model)
                        NotificationsSection(status: status, model: model, testResult: $testResult)
                    }
                } else if let error = model.errorMessage {
                    ContentUnavailableView {
                        Label("Can't reach the checker", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Try again") { Task { await model.refresh() } }
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Muo Duo")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    if model.isWorking {
                        ProgressView()
                    } else {
                        Button("Check now", systemImage: "arrow.clockwise") {
                            Task { await model.checkNow() }
                        }
                    }
                }
            }
            .refreshable { await model.refresh() }
        }
        .task(id: scenePhase) {
            if scenePhase == .active { await model.refresh() }
        }
        .alert(testResult ?? "", isPresented: Binding(get: { testResult != nil }, set: { if !$0 { testResult = nil } })) {
            Button("OK") {}
        }
    }
}

private struct CheckerSection: View {
    let status: Status
    let model: StatusModel

    var body: some View {
        Section {
            state
            if let date = status.health.lastSuccessAt {
                LabeledContent("Last good check") { Text(date, format: .relative(presentation: .named)) }
            }
            if let date = status.nextCheckAt {
                LabeledContent("Next check") { Text(date, format: .relative(presentation: .named)) }
            }
            LabeledContent("Postcode", value: status.config.postcode)
            Toggle("Paused", isOn: Binding(
                get: { status.config.paused },
                set: { paused in Task { await model.setPaused(paused) } }
            ))
            .disabled(model.isWorking)
        }
    }

    @ViewBuilder private var state: some View {
        if status.config.paused {
            Label("Paused", systemImage: "pause.circle.fill").foregroundStyle(.secondary)
        } else if status.health.consecutiveFailures > 0 {
            VStack(alignment: .leading, spacing: 4) {
                Label("Failing (\(status.health.consecutiveFailures) in a row)", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                if let error = status.health.lastError {
                    Text(error).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } else if status.resultsMayBeStale {
            VStack(alignment: .leading, spacing: 4) {
                Label("Results may be stale", systemImage: "questionmark.circle.fill")
                    .foregroundStyle(.yellow)
                Text("Apple is answering, but even iPhone 17 shows no stock nearby.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        } else {
            Label("Watching every \(status.config.intervalSeconds)s", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        }
    }
}

private struct StockSection: View {
    let status: Status

    var body: some View {
        Section("In stock") {
            if status.sightings.isEmpty {
                Text("Nothing near \(status.config.postcode) right now").foregroundStyle(.secondary)
            }
            ForEach(status.sightings, id: \.self) { sighting in
                let model = status.model(for: sighting.part)
                Link(destination: model?.url ?? URL(string: "https://www.apple.com/uk/shop/buy-iphone")!) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model?.name ?? sighting.part).font(.headline)
                        Text("\(sighting.storeName) · \(sighting.quote)").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

private struct ModelsSection: View {
    let status: Status
    let model: StatusModel

    var body: some View {
        Section("Watching") {
            ForEach(status.models) { phone in
                Toggle(phone.name, isOn: Binding(
                    get: { status.config.parts.contains(phone.part) },
                    set: { on in Task { await model.setWatching(phone.part, on) } }
                ))
            }
        }
        .disabled(model.isWorking)
    }
}

private struct NotificationsSection: View {
    let status: Status
    let model: StatusModel
    @Binding var testResult: String?

    var body: some View {
        Section("Notifications") {
            LabeledContent("This iPhone", value: registration)
            LabeledContent("Devices registered", value: "\(status.devices)")
            if model.notificationsAllowed == false {
                Button("Notifications are off: open Settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            Button("Send test notification") {
                Task { testResult = await model.sendTest() }
            }
        }
    }

    private var registration: String {
        switch model.registration {
        case .pending: "Registering…"
        case .registered: "Registered (\(AppConfig.apnsEnvironment))"
        case let .failed(message): "Failed: \(message)"
        }
    }
}
