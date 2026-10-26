import SwiftUI

/// A slowly drifting sky behind everything, in the colours of the selected finish's wallpaper, for the
/// glass to refract. Night Sky gets stars.
struct Backdrop: View {
    let finish: Finish
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            ZStack {
                ForEach(Finish.allCases) { option in
                    sky(option.palette, t: t).opacity(option == finish ? 1 : 0)
                }
                Stars(t: t).opacity(finish == .nightSky ? 1 : 0)
            }
        }
        .animation(.easeInOut(duration: 0.9), value: finish)
        .ignoresSafeArea()
    }

    private func sky(_ colors: [Color], t: Double) -> MeshGradient {
        func drift(_ phase: Double, _ amount: Double) -> Float { Float(sin(t / 7 + phase) * amount) }
        return MeshGradient(width: 3, height: 3, points: [
            [0, 0], [0.5, 0], [1, 0],
            [0, 0.45 + drift(0, 0.05)], [0.5 + drift(1, 0.08), 0.5 + drift(2, 0.06)], [1, 0.42 + drift(3, 0.05)],
            [0, 1], [0.5, 1], [1, 1],
        ], colors: colors)
    }
}

private struct Stars: View {
    let t: Double

    var body: some View {
        Canvas { context, size in
            // The same stars every frame: a fixed seed, only their brightness changes.
            var seed: UInt64 = 7
            func random() -> Double {
                seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
                return Double(seed >> 33) / Double(1 << 31)
            }
            let band = size.height * 0.42
            for i in 0..<80 {
                let x = random() * size.width
                let y = random() * band
                let radius = 0.4 + random() * 1.0
                let twinkle = 0.5 + 0.5 * sin(t * (0.5 + random()) + Double(i))
                context.opacity = (0.35 + 0.65 * twinkle) * (1 - y / band) * 0.85
                context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: radius * 2, height: radius * 2)), with: .color(.white))
            }
        }
        .allowsHitTesting(false)
    }
}
