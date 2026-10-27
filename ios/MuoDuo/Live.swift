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
}

/// "LIVE", with a dot that ripples on every check, for as long as the Worker is answering and checking
/// Apple. Anything else inverts it into a solid pill saying what's wrong.
struct LiveBadge: View {
    let model: StatusModel
    let status: Status

    var body: some View {
        let problem = problem
        HStack(spacing: 7) {
            if let problem {
                Image(systemName: problem.symbol)
                    .font(.caption2.weight(.heavy))
                    // The only colour: orange for something wrong, none for a deliberate pause.
                    .foregroundStyle(problem == .paused ? Color(.systemBackground) : .orange)
                    .accessibilityHidden(true)
                    .transition(.scale.combined(with: .opacity))
            } else {
                LiveDot(color: .green, trigger: model.pulses)
                    .transition(.scale.combined(with: .opacity))
            }
            Text(problem?.label ?? "LIVE")
                .font(.footnote.weight(.bold))
                .tracking(0.8)
                .contentTransition(.interpolate)
        }
        .foregroundStyle(problem == nil ? Color.primary : Color(.systemBackground))
        .padding(.horizontal, 14)
        .frame(height: 44)
        .fixedSize()
        // Solid rather than tinted glass, which would wash out the orange.
        .background(Color.primary.opacity(problem == nil ? 0 : 1), in: .capsule)
        .glassEffect(problem == nil ? .regular : .identity, in: .capsule)
        .animation(.smooth, value: problem)
        .accessibilityElement(children: .combine)
    }

    /// Worst first. Slowed still counts as live: checks are getting through, just less often.
    private var problem: Problem? {
        if model.errorMessage != nil { return .offline }
        if status.config.paused { return .paused }
        if status.health.consecutiveFailures > 0 { return .failing }
        if stopped { return .stopped }
        if status.resultsMayBeStale { return .stale }
        return nil
    }

    /// The Worker answered, but its next check is over 2 minutes overdue or it has none. The alarm is cleared
    /// while a check runs, so then it goes by the last check, with room for a slowed pace.
    private var stopped: Bool {
        guard let receivedAt = model.receivedAt, let due = status.nextCheckAt ?? status.health.lastCheckAt else { return true }
        return receivedAt.timeIntervalSince(due) > 120
    }

    private enum Problem {
        case offline, paused, failing, stopped, stale

        var label: String {
            switch self {
            case .offline: "OFFLINE"
            case .paused: "PAUSED"
            case .failing: "FAILING"
            case .stopped: "STOPPED"
            case .stale: "STALE"
            }
        }

        var symbol: String {
            switch self {
            case .offline: "wifi.slash"
            case .paused: "pause.fill"
            case .failing: "exclamationmark"
            case .stopped: "stop.fill"
            case .stale: "questionmark"
            }
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

/// A glass bar along the bottom showing each check as it happens. Tapping it opens the live activity.
struct Ticker: View {
    let model: StatusModel
    let status: Status
    let finish: Finish
    let open: () -> Void

    var body: some View {
        let check = model.liveChecks.first
        HStack(spacing: 14) {
            Button(action: open) {
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
                                Text("\(status.subject(of: check)) · \(check.ms) ms").font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text("Waiting for the first check").font(.subheadline.weight(.semibold))
                            }
                        }
                        // One line each, so the capsule keeps its height. Long area names shrink a little first.
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .id(status.config.paused ? "paused" : check?.id)
                        .transition(.push(from: .bottom))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .clipped()

                    if !status.config.paused {
                        Image(systemName: "chevron.up")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityHint("Shows live activity")

            if status.config.paused {
                Button("Resume") { Task { await model.setPaused(false) } }
                    .buttonStyle(.glassProminent)
                    .disabled(model.isWorking)
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
