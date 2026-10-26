import Foundation
import Observation

@Observable
final class StatusModel {
    enum Registration: Equatable {
        case pending
        case registered
        case failed(String)
    }

    private(set) var status: Status?
    private(set) var errorMessage: String?
    private(set) var isWorking = false
    private(set) var registration = Registration.pending
    var notificationsAllowed: Bool?

    private let api = APIClient()

    func refresh() async {
        await perform { try await $0.status() }
    }

    func checkNow() async {
        await perform { try await $0.checkNow() }
    }

    func setPaused(_ paused: Bool) async {
        await perform { try await $0.updateConfig(ConfigPatch(paused: paused)) }
    }

    func setWatching(_ part: String, _ watching: Bool) async {
        guard var parts = status?.config.parts else { return }
        parts.removeAll { $0 == part }
        if watching { parts.append(part) }
        await perform { try await $0.updateConfig(ConfigPatch(parts: parts)) }
    }

    func register(deviceToken: Data) async {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        do {
            status = try await api.registerDevice(token: token, environment: AppConfig.apnsEnvironment)
            registration = .registered
        } catch {
            registration = .failed(error.localizedDescription)
        }
    }

    func registrationFailed(_ error: any Error) {
        registration = .failed(error.localizedDescription)
    }

    /// Returns a message to show the user.
    func sendTest() async -> String {
        do {
            let result = try await api.sendTestNotification()
            return result.delivered ? "Sent. It should arrive in a few seconds." : "Not sent: \(result.errors.joined(separator: ", "))"
        } catch {
            return error.localizedDescription
        }
    }

    private func perform(_ request: (APIClient) async throws -> Status) async {
        isWorking = true
        defer { isWorking = false }
        do {
            status = try await request(api)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
