import Foundation

struct APIClient {
    enum APIError: LocalizedError {
        case notConfigured
        case server(status: Int, message: String?)

        var errorDescription: String? {
            switch self {
            case .notConfigured: "Set MUODUO_API_TOKEN in ios/Config/Secrets.xcconfig and rebuild."
            case let .server(status, message): message ?? "The Worker returned HTTP \(status)."
            }
        }
    }

    private struct ErrorBody: Decodable { let error: String }
    private struct DeviceRegistration: Encodable { let token: String; let environment: String }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let value = try decoder.singleValueContainer().decode(String.self)
            return try Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(value)
        }
        return decoder
    }()

    func status() async throws -> Status {
        try await send("GET", "status")
    }

    func registerDevice(token: String, environment: String) async throws -> Status {
        try await send("POST", "devices", body: DeviceRegistration(token: token, environment: environment))
    }

    func updateConfig(_ patch: ConfigPatch) async throws -> Status {
        try await send("PUT", "config", body: patch)
    }

    func checkNow() async throws -> Status {
        try await send("POST", "check")
    }

    func sendTestNotification() async throws -> TestResult {
        try await send("POST", "test-notification")
    }

    private func send<Response: Decodable>(_ method: String, _ path: String, body: (any Encodable)? = nil) async throws -> Response {
        guard let base = AppConfig.apiBaseURL, !AppConfig.apiToken.isEmpty else { throw APIError.notConfigured }

        var request = URLRequest(url: base.appending(path: path))
        request.httpMethod = method
        request.setValue("Bearer \(AppConfig.apiToken)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpBody = try JSONEncoder().encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw APIError.server(status: status, message: (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error)
        }
        return try Self.decoder.decode(Response.self, from: data)
    }
}
