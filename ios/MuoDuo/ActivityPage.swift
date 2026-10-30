import SwiftUI

/// From the ticker: each area as it's checked, and every request the Worker has just made to Apple, as it happens.
struct ActivityPage: View {
    let model: StatusModel
    let finish: Finish
    let chrome: PageChrome
    let close: () -> Void

    @Environment(\.room) private var room
    /// The last check, big enough to read from across the desk.
    @ScaledMetric(relativeTo: .largeTitle) private var glanceSize: CGFloat = 34

    private var tent: Bool { room == .tent }

    var body: some View {
        Group {
            if tent {
                // Stood up like a tent: how it's going on the leading side, every request beside it.
                HStack(alignment: .top, spacing: 16) {
                    ScrollView {
                        ticking { status, now in
                            VStack(spacing: 16) {
                                summary(status, now: now)
                                areas(status, now: now)
                            }
                        }
                    }
                    .frame(width: 260)
                    ScrollView {
                        ticking { status, now in feed(status, now: now) }
                    }
                }
                .padding(.horizontal, 16)
            } else {
                ScrollView {
                    ticking { status, now in
                        VStack(spacing: 16) {
                            summary(status, now: now)
                            areas(status, now: now)
                            feed(status, now: now)
                        }
                    }
                    .padding(.horizontal, 16)
                    .readableWidth()
                }
            }
        }
        .scrollIndicators(.hidden)
        // Beside home, home's sky is already behind it.
        .background {
            if chrome.isBar { Backdrop(finish: finish) }
        }
        .pageChrome(chrome, title: "Live activity", close: close)
        .toolbar {
            if chrome.isBar, let status = model.status {
                ToolbarItem(placement: .topBarLeading) {
                    LiveBadge(model: model, status: status)
                }
                .sharedBackgroundVisibility(.hidden)
            }
        }
    }

    /// Every second, so each "ago" counts up.
    @ViewBuilder private func ticking(@ViewBuilder _ content: @escaping (Status, Date) -> some View) -> some View {
        if let status = model.status {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                content(status, context.date)
            }
            .padding(.bottom, 24)
        }
    }

    private func summary(_ status: Status, now: Date) -> some View {
        let answered = model.liveChecks.filter(\.ok)
        let average = answered.isEmpty ? nil : answered.reduce(0) { $0 + $1.ms } / answered.count
        let last = model.liveChecks.first.map { ago($0.at, now: now) } ?? "–"
        let pace = "every \(status.pace.intervalSeconds)s"
        let answers = average.map { "\($0) ms" } ?? "–"
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
            if tent {
                VStack(alignment: .leading, spacing: 2) {
                    Text(last)
                        .font(.system(size: glanceSize, weight: .bold, design: .rounded))
                        .foregroundStyle(status.state == .live ? Color.green : .primary)
                        .monospacedDigit()
                        .contentTransition(.numericText())
                    Text("Last check").font(.subheadline).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                VStack(spacing: 8) {
                    row("Each size, each area", value: pace)
                    row("Apple answers in", value: answers)
                }
            } else {
                HStack(alignment: .top) {
                    stat("Last check", value: last)
                    stat("Each size, each area", value: pace)
                    stat("Apple answers in", value: answers)
                }
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

    private func row(_ title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value).fontWeight(.semibold).monospacedDigit().contentTransition(.numericText())
        }
        .font(.subheadline)
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
