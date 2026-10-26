import SwiftUI

/// The phone in the selected colour, with the Worker's checks rippling out from behind it.
struct Hero: View {
    let model: StatusModel
    let status: Status
    @Binding var finish: Finish

    private static let width: CGFloat = 340

    var body: some View {
        let inStock = status.sightings.contains { status.model(for: $0.part)?.finish == finish }
        VStack(spacing: 20) {
            VStack(spacing: 6) {
                Text("iPhone Duo")
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                headline
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .contentTransition(.numericText())
            }
            .padding(.top, 8)

            phone
                .frame(width: Self.width, height: Self.width * 0.62)
                .background {
                    Radar(color: inStock ? .green : finish.accent, trigger: model.pulses)
                        .frame(width: 400, height: 400)
                }

            FinishPicker(selection: $finish, status: status)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var headline: some View {
        switch status.state {
        case .paused: Text("Paused · not checking")
        case .failing: Text("Can't reach Apple right now")
        default:
            if status.sightings.isEmpty {
                Text("Watching \(status.storeCount) Apple Stores in \(status.areas.count) areas")
            } else {
                Text("In stock now").foregroundStyle(.green)
            }
        }
    }

    private var phone: some View {
        ZStack {
            Ellipse()
                .fill(.black.opacity(finish == .nightSky ? 0.5 : 0.22))
                .frame(width: Self.width * 0.72, height: 18)
                .blur(radius: 14)
                .offset(y: Self.width * 0.29)

            Group {
                if let image = model.image(for: finish) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .floating()
                        .accessibilityLabel("iPhone Duo in \(finish.name)")
                } else {
                    ProgressView()
                }
            }
            // Apple's shots have a lot of empty space above and below the phone; only the middle is laid out.
            .frame(width: Self.width, height: Self.width * 1112 / 940)
            .id(finish)
            .transition(.blurReplace.combined(with: .scale(0.94)))
        }
    }
}

private extension View {
    func floating() -> some View { modifier(Floating()) }
}

private struct Floating: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.phaseAnimator([false, true]) { content, up in
                content.offset(y: up ? -5 : 3)
            } animation: { _ in .easeInOut(duration: 3.2) }
        }
    }
}

/// Night Sky / Star White, as glass capsules that light up in the selected colour.
struct FinishPicker: View {
    @Binding var selection: Finish
    let status: Status

    var body: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                ForEach(Finish.allCases) { finish in
                    let selected = finish == selection
                    let inStock = status.sightings.contains { status.model(for: $0.part)?.finish == finish }
                    Button {
                        withAnimation(.smooth(duration: 0.6)) { selection = finish }
                    } label: {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(finish.swatch.gradient)
                                .frame(width: 18, height: 18)
                                .overlay(Circle().strokeBorder(.primary.opacity(0.25), lineWidth: 1))
                            Text(finish.name)
                                .font(.subheadline.weight(selected ? .semibold : .medium))
                            if inStock {
                                Circle().fill(.green).frame(width: 7, height: 7)
                            }
                        }
                        .foregroundStyle(selected ? .primary : .secondary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .contentShape(.capsule)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(selected ? .regular.tint(finish.accent.opacity(0.4)).interactive() : .regular.interactive(), in: .capsule)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }
}
