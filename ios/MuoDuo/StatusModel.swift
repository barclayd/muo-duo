import Foundation
import Observation
import UIKit

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

    /// The Worker's checks, newest first, revealed one at a time as they happened.
    private(set) var liveChecks: [Status.Check] = []
    /// Goes up once per check as it's revealed, to drive pulses.
    private(set) var pulses = 0
    /// The same, per area name.
    private(set) var areaPulses: [String: Int] = [:]
    /// Apple's product shots, by URL.
    private(set) var images: [URL: UIImage] = [:]

    private let api = APIClient()
    private var newestCheck: Date?
    /// Bumped by every change the user makes, so a poll that started before it can't undo it.
    private var generation = 0
    private var loadingImages: Set<URL> = []

    /// Polls until cancelled. The Worker does the checking; this only watches it.
    func poll() async {
        while !Task.isCancelled {
            let started = generation
            do {
                let status = try await api.status()
                if started == generation, !isWorking { absorb(status) }
                errorMessage = nil
            } catch is CancellationError {
                return
            } catch {
                if (error as? URLError)?.code != .cancelled { errorMessage = error.localizedDescription }
            }
            try? await Task.sleep(for: .seconds(2))
        }
    }

    func refresh() async {
        await perform { try await $0.status() }
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
            absorb(try await api.registerDevice(token: token, environment: AppConfig.apnsEnvironment))
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

    func image(for finish: Finish) -> UIImage? {
        status?.models(in: finish).first.flatMap { images[$0.imageUrl] }
    }

    private func perform(_ request: (APIClient) async throws -> Status) async {
        generation += 1
        isWorking = true
        defer { isWorking = false }
        do {
            absorb(try await request(api))
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func absorb(_ status: Status) {
        self.status = status
        loadImages(Set(status.models.map(\.imageUrl)))

        guard let newest = status.recentChecks.first else { return }
        guard let previous = newestCheck else {
            // First load: show what's already happened without replaying it.
            liveChecks = Array(status.recentChecks.prefix(Self.liveCheckCount))
            newestCheck = newest.at
            return
        }
        newestCheck = max(previous, newest.at)

        // Replay checks made since the last poll at the pace they happened, so each one pulses on its own.
        // After a long gap (the app was in the background) only the last few seconds are replayed.
        let fresh = status.recentChecks.filter { $0.at > previous }.reversed()
        guard let first = fresh.first(where: { newest.at.timeIntervalSince($0.at) < 3 }) else { return }
        for check in fresh where check.at < first.at { reveal(check, pulse: false) }
        for check in fresh where check.at >= first.at {
            Task {
                try? await Task.sleep(for: .seconds(check.at.timeIntervalSince(first.at)))
                reveal(check, pulse: true)
            }
        }
    }

    private func reveal(_ check: Status.Check, pulse: Bool) {
        guard !liveChecks.contains(check) else { return }
        liveChecks.insert(check, at: 0)
        liveChecks.removeLast(max(0, liveChecks.count - Self.liveCheckCount))
        guard pulse else { return }
        pulses += 1
        areaPulses[check.area, default: 0] += 1
    }

    private static let liveCheckCount = 12

    // Apple's CDN only lets these be cached for about a minute, so they're kept on disk here.
    private func loadImages(_ urls: Set<URL>) {
        for url in urls where images[url] == nil && !loadingImages.contains(url) {
            loadingImages.insert(url)
            Task {
                defer { loadingImages.remove(url) }
                let file = URL.cachesDirectory.appending(path: "\(url.lastPathComponent).png")
                let data: Data
                if let saved = try? Data(contentsOf: file) {
                    data = saved
                } else {
                    guard let (fetched, response) = try? await URLSession.shared.data(from: url),
                          (response as? HTTPURLResponse)?.statusCode == 200 else { return }
                    try? fetched.write(to: file)
                    data = fetched
                }
                images[url] = await UIImage(data: data)?.byPreparingForDisplay()
            }
        }
    }
}
