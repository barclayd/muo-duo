import SwiftUI

extension LiveState {
    var color: Color {
        switch self {
        case .live: .green
        case .paused: .gray
        case .failing: .orange
        case .stale, .slowed: .yellow
        }
    }

    var badge: String {
        switch self {
        case .live: "LIVE"
        case .paused: "PAUSED"
        case .failing: "FAILING"
        case .stale: "CHECK"
        case .slowed: "SLOWED"
        }
    }
}

/// "LIVE · 1s ago", with a dot that ripples on every check the Worker makes.
struct LiveBadge: View {
    let state: LiveState
    let lastCheck: Date?
    let pulses: Int

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HStack(spacing: 7) {
                LiveDot(color: state.color, trigger: pulses)
                Text(state.badge).font(.footnote.weight(.bold)).tracking(0.8)
                if let lastCheck, state != .paused {
                    // Sized for the longest label, so going from "now" to "2s ago" doesn't resize the toolbar.
                    ZStack(alignment: .leading) {
                        Text("00m ago").hidden()
                        Text(ago(lastCheck, now: context.date)).contentTransition(.numericText())
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                }
            }
            .padding(.horizontal, 6)
            .fixedSize()
            .animation(.snappy, value: context.date)
        }
        .accessibilityElement(children: .combine)
    }

    private func ago(_ date: Date, now: Date) -> String {
        let seconds = Int(max(0, now.timeIntervalSince(date)))
        switch seconds {
        case ..<2: return "now"
        case ..<60: return "\(seconds)s ago"
        case ..<3600: return "\(seconds / 60)m ago"
        default: return "\(seconds / 3600)h ago"
        }
    }
}

struct LiveDot: View {
    let color: Color
    let trigger: Int
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .background { Ripple(color: color, trigger: trigger, reach: 3.2) }
    }
}

/// A ring of colour that swells and fades each time `trigger` changes.
struct Ripple: View {
    let color: Color
    let trigger: Int
    var reach: CGFloat = 2.6
    var duration: Double = 0.9

    private struct Value {
        var scale: CGFloat = 1
        var opacity: Double = 0
    }

    var body: some View {
        Circle()
            .fill(color)
            .keyframeAnimator(initialValue: Value(), trigger: trigger) { content, value in
                content.scaleEffect(value.scale).opacity(value.opacity)
            } keyframes: { _ in
                KeyframeTrack(\.scale) {
                    MoveKeyframe(1)
                    CubicKeyframe(reach, duration: duration)
                }
                KeyframeTrack(\.opacity) {
                    MoveKeyframe(0.6)
                    CubicKeyframe(0, duration: duration)
                }
            }
    }
}

/// Rings that spread out from behind the phone, one per check, over a slowly breathing glow. They
/// overlap, so each is its own view rather than one restarting animation.
struct Radar: View {
    let color: Color
    let trigger: Int
    @State private var rings: [Int] = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            ForEach([0.42, 0.68, 0.94], id: \.self) { scale in
                Circle().strokeBorder(color.opacity(0.16), lineWidth: 1).scaleEffect(scale)
            }
            Circle()
                .fill(RadialGradient(colors: [color.opacity(0.42), color.opacity(0)], center: .center, startRadius: 0, endRadius: 190))
                .phaseAnimator([false, true]) { content, swell in
                    content.scaleEffect(reduceMotion ? 1 : swell ? 1.06 : 0.9).opacity(swell ? 1 : 0.7)
                } animation: { _ in .easeInOut(duration: 2.6) }
            ForEach(rings, id: \.self) { _ in Ring(color: color) }
        }
        .aspectRatio(1, contentMode: .fit)
        .onChange(of: trigger) { _, ring in
            guard !reduceMotion else { return }
            rings.append(ring)
            Task {
                try? await Task.sleep(for: .seconds(Ring.duration + 0.1))
                rings.removeAll { $0 == ring }
            }
        }
        .allowsHitTesting(false)
    }

    private struct Ring: View {
        static let duration = 2.6
        let color: Color
        @State private var spread = false

        var body: some View {
            Circle()
                .strokeBorder(color, lineWidth: 2)
                .background(Circle().fill(color.opacity(0.08)))
                .scaleEffect(spread ? 1.05 : 0.3)
                .opacity(spread ? 0 : 0.9)
                .onAppear { withAnimation(.easeOut(duration: Self.duration)) { spread = true } }
        }
    }
}

/// A glass bar along the bottom showing each check as it happens.
struct Ticker: View {
    let model: StatusModel
    let status: Status
    let finish: Finish

    var body: some View {
        let check = model.liveChecks.first
        HStack(spacing: 14) {
            Image(systemName: status.config.paused ? "pause.fill" : "dot.radiowaves.left.and.right")
                .font(.title3)
                .foregroundStyle(finish.accent)
                .symbolEffect(.variableColor.iterative, options: .repeating, isActive: status.state == .live)
                .frame(width: 28)

            // The old line slides out as the new one slides in, so this is clipped to the text, not the capsule.
            ZStack(alignment: .leading) {
                VStack(alignment: .leading, spacing: 1) {
                    if status.config.paused {
                        Text("Checking is paused").font(.subheadline.weight(.semibold))
                        Text("You won't get stock alerts").font(.caption).foregroundStyle(.secondary)
                    } else if let check {
                        Text(check.ok ? "Checked \(check.area)" : "\(check.area) didn't answer").font(.subheadline.weight(.semibold))
                        Text("\(status.model(for: check.part)?.name ?? check.part) · \(check.ms) ms").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("Waiting for the first check").font(.subheadline.weight(.semibold))
                    }
                }
                .id(status.config.paused ? "paused" : check?.id)
                .transition(.push(from: .bottom))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()

            if status.config.paused {
                Button("Resume") { Task { await model.setPaused(false) } }
                    .buttonStyle(.glassProminent)
                    .disabled(model.isWorking)
            } else {
                Text("every \(status.pace.intervalSeconds)s")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
        .padding(.leading, 18)
        .padding(.trailing, 14)
        .padding(.vertical, 12)
        .glassEffect(.regular.interactive(), in: .capsule)
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
        .animation(.snappy, value: check?.id)
        .animation(.snappy, value: status.config.paused)
    }
}
