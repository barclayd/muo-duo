import SwiftUI
import UIKit

struct SettingsSheet: View {
    let model: StatusModel

    @Environment(\.dismiss) private var dismiss
    @State private var testResult: String?
    @State private var sendingTest = false

    var body: some View {
        NavigationStack {
            Form {
                if let status = model.status {
                    checking(status)
                    notifications(status)
                    areas(status)
                }
                Section {
                    Text("Product images are Apple's, loaded from apple.com.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func checking(_ status: Status) -> some View {
        Section {
            Toggle("Paused", isOn: Binding(
                get: { status.config.paused },
                set: { paused in Task { await model.setPaused(paused) } }
            ))
            .disabled(model.isWorking)
            LabeledContent("Each model, each area", value: "every \(status.pace.intervalSeconds)s")
            if let date = status.health.lastSuccessAt {
                LabeledContent("Last good check") { Text(date, format: .relative(presentation: .named)) }
            }
            if let error = status.health.lastError {
                LabeledContent("Last error") { Text(error).multilineTextAlignment(.trailing) }
            }
        } header: {
            Text("Checking")
        } footer: {
            if let reason = status.pace.reason { Text("Slowed down: \(reason).") }
        }
    }

    private func notifications(_ status: Status) -> some View {
        Section {
            LabeledContent("This iPhone", value: registration)
            LabeledContent("Devices registered", value: "\(status.devices)")
            if model.notificationsAllowed == false {
                Button("Notifications are off: open Settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            Button {
                sendingTest = true
                Task {
                    testResult = await model.sendTest()
                    sendingTest = false
                }
            } label: {
                HStack {
                    Text("Send test notification")
                    Spacer()
                    if sendingTest { ProgressView() }
                }
            }
            .disabled(sendingTest)
        } header: {
            Text("Notifications")
        } footer: {
            if let testResult { Text(testResult) }
        }
    }

    private func areas(_ status: Status) -> some View {
        Section {
            ForEach(status.areas) { area in
                DisclosureGroup {
                    ForEach(area.stores) { store in
                        LabeledContent(store.name) {
                            if store.covered {
                                Text(store.number).monospaced()
                            } else {
                                Label("Not covered", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                            }
                        }
                    }
                } label: {
                    LabeledContent(area.name, value: "\(area.stores.count) stores")
                }
            }
        } header: {
            Text("Areas")
        } footer: {
            Text("Each area is one search near \(status.areas.map(\.search).formatted(.list(type: .and))). Change them with PUT /config on the Worker.")
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
