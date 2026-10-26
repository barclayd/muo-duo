import SwiftUI

/// The two iPhone Duo colours. The app takes its look from whichever is selected: Star White is the
/// dawn desert on its default wallpaper, Night Sky the same desert at dusk.
enum Finish: String, CaseIterable, Identifiable {
    case nightSky = "Night Sky"
    case starWhite = "Star White"

    var id: Self { self }
    var name: String { rawValue }

    var colorScheme: ColorScheme {
        switch self {
        case .nightSky: .dark
        case .starWhite: .light
        }
    }

    /// The colour of the phone itself, for swatches.
    var swatch: Color {
        switch self {
        case .nightSky: Color(red: 0.17, green: 0.21, blue: 0.28)
        case .starWhite: Color(red: 0.90, green: 0.89, blue: 0.87)
        }
    }

    /// Tints glass and pulses.
    var accent: Color {
        switch self {
        case .nightSky: Color(red: 0.66, green: 0.62, blue: 1.0)
        case .starWhite: Color(red: 0.74, green: 0.47, blue: 0.22)
        }
    }

    /// Sky, horizon and dunes, three by three, for the backdrop's mesh gradient.
    var palette: [Color] {
        switch self {
        case .nightSky:
            [
                .init(red: 0.06, green: 0.08, blue: 0.17), .init(red: 0.09, green: 0.11, blue: 0.23), .init(red: 0.06, green: 0.08, blue: 0.16),
                .init(red: 0.33, green: 0.27, blue: 0.40), .init(red: 0.50, green: 0.35, blue: 0.46), .init(red: 0.29, green: 0.25, blue: 0.38),
                .init(red: 0.13, green: 0.11, blue: 0.17), .init(red: 0.20, green: 0.17, blue: 0.24), .init(red: 0.11, green: 0.10, blue: 0.15),
            ]
        case .starWhite:
            [
                .init(red: 0.66, green: 0.76, blue: 0.87), .init(red: 0.77, green: 0.83, blue: 0.90), .init(red: 0.70, green: 0.79, blue: 0.88),
                .init(red: 0.93, green: 0.84, blue: 0.75), .init(red: 0.96, green: 0.88, blue: 0.79), .init(red: 0.91, green: 0.81, blue: 0.71),
                .init(red: 0.84, green: 0.77, blue: 0.67), .init(red: 0.80, green: 0.72, blue: 0.62), .init(red: 0.75, green: 0.67, blue: 0.57),
            ]
        }
    }
}
