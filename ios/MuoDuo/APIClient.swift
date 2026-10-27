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
    private struct TestRequest: Encodable { let alarm: Bool; let delaySeconds: Int }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let value = try decoder.singleValueContainer().decode(String.self)
            return try Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(value)
        }
        return decoder
    }()

    /// Short timeout: the app polls this every 2s, and a hung request should read as "offline" quickly.
    func status() async throws -> Status {
        try await send("GET", "status", timeout: 8)
    }

    func registerDevice(token: String, environment: String) async throws -> Status {
        try await send("POST", "devices", body: DeviceRegistration(token: token, environment: environment))
    }

    /// This iPhone's own settings.
    func updateDevice(_ patch: DevicePatch) async throws -> Status {
        try await send("PUT", "device", body: patch)
    }

    /// Settings shared by every phone. Only the owner's key can change them.
    func updateConfig(_ patch: ConfigPatch) async throws -> Status {
        try await send("PUT", "config", body: patch)
    }

    func checkNow() async throws -> Status {
        try await send("POST", "check")
    }

    /// With a delay, the Worker answers straight away and sends it later, so it can't say whether it arrived.
    func sendTestNotification(alarm: Bool = false, delaySeconds: Int = 0) async throws -> TestResult {
        try await send("POST", "test-notification", body: TestRequest(alarm: alarm, delaySeconds: delaySeconds))
    }

    private func send<Response: Decodable>(_ method: String, _ path: String, body: (any Encodable)? = nil, timeout: TimeInterval = 60) async throws -> Response {
        guard let base = AppConfig.apiBaseURL, !AppConfig.apiToken.isEmpty else { throw APIError.notConfigured }

        var request = URLRequest(url: base.appending(path: path))
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue("Bearer \(AppConfig.apiToken)", forHTTPHeaderField: "Authorization")
        request.setValue(AppConfig.deviceID, forHTTPHeaderField: "x-device-id")
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
