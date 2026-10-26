import Foundation

enum AppConfig {
    static let apiBaseURL = URL(string: info("MuoDuoAPIBaseURL"))
    static let apiToken = info("MuoDuoAPIToken")

    /// Which APNs host the Worker must use for this build's device token.
    /// Builds run from Xcode carry a development profile and use the sandbox; App Store and
    /// TestFlight builds have no embedded profile and use production.
    static let apnsEnvironment: String = {
        #if targetEnvironment(simulator)
        return "sandbox"
        #else
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let profile = String(data: data, encoding: .isoLatin1) else { return "production" }
        let development = #"<key>aps-environment</key>\s*<string>development</string>"#
        return profile.range(of: development, options: .regularExpression) == nil ? "production" : "sandbox"
        #endif
    }()

    private static func info(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespaces)
    }
}
