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
