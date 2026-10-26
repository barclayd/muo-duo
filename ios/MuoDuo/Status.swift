import Foundation

/// Mirrors `GET /status` on the Worker.
struct Status: Decodable {
    struct Config: Decodable {
        let postcode: String
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

    /// How often each model is actually checked. Slower than configured after Apple pushes back.
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

    struct Model: Decodable, Identifiable {
        let part: String
        let name: String
        let capacity: String
        let url: URL
        var id: String { part }
    }

    let config: Config
    let health: Health
    let pace: Pace
    /// Apple is answering, but even a model that's always in stock shows nothing, so "nothing in stock" may be wrong.
    let resultsMayBeStale: Bool
    let sightings: [Sighting]
    let devices: Int
    let nextCheckAt: Date?
    let models: [Model]

    func model(for part: String) -> Model? {
        models.first { $0.part == part }
    }
}

struct ConfigPatch: Encodable {
    var parts: [String]?
    var paused: Bool?
}

struct TestResult: Decodable {
    let delivered: Bool
    let errors: [String]
}
