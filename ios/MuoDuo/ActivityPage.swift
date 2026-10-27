import SwiftUI

/// Full screen from the ticker: each area as it's checked, and every request the Worker has just made to
/// Apple, as it happens.
struct ActivityPage: View {
    let model: StatusModel
    let finish: Finish

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                if let status = model.status {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        VStack(spacing: 16) {
                            summary(status, now: context.date)
                            areas(status, now: context.date)
                            feed(status, now: context.date)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .scrollIndicators(.hidden)
            .background { Backdrop(finish: finish) }
            .navigationTitle("Live activity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let status = model.status {
                    ToolbarItem(placement: .topBarLeading) {
                        LiveBadge(model: model, status: status)
                    }
                    .sharedBackgroundVisibility(.hidden)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
        .preferredColorScheme(finish.colorScheme)
    }

    private func summary(_ status: Status, now: Date) -> some View {
        let answered = model.liveChecks.filter(\.ok)
        let average = answered.isEmpty ? nil : answered.reduce(0) { $0 + $1.ms } / answered.count
        return VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                LiveDot(color: status.state.color, trigger: model.pulses, size: 9)
                Text(headline(status)).font(.headline)
                Spacer()
                if status.config.paused, status.isOwner {
                    Button("Resume") { Task { await model.setPaused(false) } }
                        .buttonStyle(.glassProminent)
                        .disabled(model.isWorking)
                }
            }
            HStack(alignment: .top) {
                stat("Last check", value: model.liveChecks.first.map { ago($0.at, now: now) } ?? "–")
                stat("Each size, each area", value: "every \(status.pace.intervalSeconds)s")
                stat("Apple answers in", value: average.map { "\($0) ms" } ?? "–")
            }
        }
        .card()
    }

    private func headline(_ status: Status) -> String {
        switch status.state {
        case .paused: "Paused · not checking"
        case .failing: "Apple isn't answering"
        case .stale: "Checking, but results may be stale"
        case .live, .slowed: "Checking \(status.storeCount) stores in \(status.areas.count) areas"
        }
    }

    private func stat(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(.title3, design: .rounded).weight(.semibold))
                .monospacedDigit()
                .contentTransition(.numericText())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// Each area, rippling as it's asked about.
    private func areas(_ status: Status, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(title: "Areas", symbol: "mappin.and.ellipse", trailing: "\(status.storeCount) stores")
            VStack(spacing: 0) {
                ForEach(status.areas) { area in
                    if area.id != status.areas.first?.id { Divider().padding(.leading, 34) }
                    let last = model.liveChecks.first { $0.area == area.name }
                    HStack(spacing: 12) {
                        LiveDot(color: finish.accent, trigger: model.areaPulses[area.name] ?? 0, size: 8)
                            .frame(width: 22)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(area.name).font(.subheadline.weight(.semibold))
                            Text("\(area.stores.count) stores near \(area.search)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(last.map { ago($0.at, now: now) } ?? "–")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .card()
    }

    private func feed(_ status: Status, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            CardHeader(title: "Requests to Apple", symbol: "waveform.path.ecg", trailing: "latest \(model.liveChecks.count)")
            VStack(spacing: 10) {
                ForEach(model.liveChecks) { check in
                    CheckRow(check: check, status: status, finish: finish, now: now)
                        .transition(.asymmetric(insertion: .push(from: .top), removal: .identity))
                }
            }
            .animation(.snappy, value: model.liveChecks.first?.id)
        }
        .card()
    }

    private func ago(_ date: Date, now: Date) -> String {
        "\(Int(max(0, now.timeIntervalSince(date))))s ago"
    }
}

/// One request the Worker made to Apple.
private struct CheckRow: View {
    let check: Status.Check
    let status: Status
    let finish: Finish
    let now: Date

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: check.ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                .foregroundStyle(check.ok ? finish.accent : .orange)
            VStack(alignment: .leading, spacing: 1) {
                Text(check.area).font(.subheadline.weight(.semibold))
                Text(status.subject(of: check)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text(check.ok ? "\(check.ms) ms" : "failed").font(.caption.weight(.medium)).monospacedDigit()
                Text("\(Int(max(0, now.timeIntervalSince(check.at))))s ago").font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        }
        .accessibilityElement(children: .combine)
    }
}
