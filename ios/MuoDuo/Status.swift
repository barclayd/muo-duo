import CoreLocation
import Foundation

/// Mirrors `GET /status` on the Worker.
struct Status: Decodable {
    struct Config: Decodable {
        let parts: [String]
        let intervalSeconds: Int
        let paused: Bool
    }

    struct Health: Decodable {
        let lastCheckAt: Date?
        let lastSuccessAt: Date?
        let lastError: String?
        let consecutiveFailures: Int
    }

    /// How often each model is actually checked in each area. Slower than configured after Apple pushes back.
    struct Pace: Decodable {
        let intervalSeconds: Int
        let reason: String?
    }

    struct Sighting: Decodable, Hashable {
        let part: String
        let storeNumber: String
        let storeName: String
        let quote: String
    }

    /// A search the Worker asks Apple about, and the stores near it that it watches.
    struct Area: Decodable, Identifiable {
        let name: String
        let search: String
        let stores: [Store]
        var id: String { search }
    }

    struct Store: Decodable, Identifiable {
        let number: String
        let name: String
        let city: String?
        let latitude: Double?
        let longitude: Double?
        /// False if Apple left the store out of its last answer, so it isn't being watched.
        let covered: Bool
        var id: String { number }

        var coordinate: CLLocationCoordinate2D? {
            guard let latitude, let longitude else { return nil }
            return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
    }

    /// A stretch of time a model was in stock at a store.
    struct Episode: Decodable, Hashable {
        let part: String
        let storeNumber: String
        let storeName: String
        let from: Date
        let to: Date
    }

    /// One request the Worker made to Apple.
    struct Check: Decodable, Identifiable, Hashable {
        let at: Date
        let area: String
        let part: String
        let ok: Bool
        let ms: Int
        var id: String { "\(at.timeIntervalSince1970)|\(area)|\(part)" }
    }

    /// Something for the stock log: a watched model coming into stock at a store or going again, or the
    /// checker stopping and starting.
    struct LogEntry: Decodable, Identifiable, Hashable {
        enum Kind: String, Decodable {
            case inStock = "in"
            case gone, failing, recovered, stale, normal, paused, resumed
        }

        let at: Date
        let kind: Kind
        let part: String?
        let storeNumber: String?
        let storeName: String?
        let quote: String?
        /// When stock that's gone came in.
        let since: Date?
        let detail: String?
        var id: String { "\(at.timeIntervalSince1970)|\(kind.rawValue)|\(part ?? "")|\(storeNumber ?? "")" }
    }

    struct Model: Decodable, Identifiable {
        let part: String
        let name: String
        let capacity: String
        let colour: String
        let url: URL
        let imageUrl: URL
        var id: String { part }
        var finish: Finish? { Finish(rawValue: colour) }
    }

    let config: Config
    let health: Health
    let pace: Pace
    /// Apple is answering, but even a model that's always in stock shows nothing, so "nothing in stock" may be wrong.
    let resultsMayBeStale: Bool
    let sightings: [Sighting]
    let areas: [Area]
    /// Most recent first.
    let lastSeen: [Episode]
    let trackingSince: Date?
    /// Most recent first.
    let log: [LogEntry]
    let loggingSince: Date?
    /// Most recent first.
    let recentChecks: [Check]
    let devices: Int
    let nextCheckAt: Date?
    let models: [Model]

    func model(for part: String) -> Model? {
        models.first { $0.part == part }
    }

    func models(in finish: Finish) -> [Model] {
        models.filter { $0.finish == finish }
    }

    func area(of store: String) -> Area? {
        areas.first { $0.stores.contains { $0.number == store } }
    }

    var storeCount: Int {
        areas.reduce(0) { $0 + $1.stores.count }
    }

    var state: LiveState {
        if config.paused { return .paused }
        if health.consecutiveFailures > 0 { return .failing(health.lastError) }
        if resultsMayBeStale { return .stale }
        if let reason = pace.reason { return .slowed(reason) }
        return .live
    }
}

enum LiveState: Equatable {
    case live
    case paused
    case failing(String?)
    case stale
    case slowed(String)
}

struct ConfigPatch: Encodable {
    var parts: [String]?
    var paused: Bool?
}

struct TestResult: Decodable {
    let delivered: Bool
    let errors: [String]
}
