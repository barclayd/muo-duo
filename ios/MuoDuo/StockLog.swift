import SwiftUI

/// Each time a watched size came into stock at a store and went again, and when the checker stopped and
/// started, so a night's stock can be read back in the morning.
struct StockLogCard: View {
    let status: Status
    let showAll: () -> Void

    private static let shown = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            CardHeader(title: "Stock log", symbol: "list.bullet.clipboard", trailing: lastDay)
            if status.log.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Nothing yet").font(.headline)
                    Text("Each time a size you watch comes into stock at one of the \(status.storeCount) stores, or goes again, it's logged here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if let since = status.loggingSince {
                        Text("Logging since \(since.formatted(date: .abbreviated, time: .shortened)).")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                ForEach(Array(status.log.prefix(Self.shown)).byDay) { day in
                    VStack(alignment: .leading, spacing: 12) {
                        Text(day.title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(day.entries) { entry in
                            LogRow(status: status, entry: entry)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                }
                if status.log.count > Self.shown {
                    Button(action: showAll) {
                        Text("Show All \(status.log.count)")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                }
            }
        }
        .card()
        .animation(.smooth, value: status.log.first?.id)
    }

    private var lastDay: String {
        let sightings = status.log.filter { $0.kind == .inStock && $0.at > .now.addingTimeInterval(-86_400) }.count
        return switch sightings {
        case 0: "None in 24h"
        case 1: "1 sighting in 24h"
        default: "\(sightings) sightings in 24h"
        }
    }
}

/// The whole log, a card per day.
struct StockLogPage: View {
    let model: StatusModel
    let finish: Finish

    var body: some View {
        ScrollView {
            if let status = model.status {
                VStack(spacing: 16) {
                    ForEach(status.log.byDay) { day in
                        VStack(alignment: .leading, spacing: 14) {
                            CardHeader(title: day.title, symbol: "calendar", trailing: sightings(day))
                            ForEach(day.entries) { entry in
                                LogRow(status: status, entry: entry)
                                    .transition(.move(edge: .top).combined(with: .opacity))
                            }
                        }
                        .card()
                    }
                    if let since = status.loggingSince {
                        Text("Logging since \(since.formatted(date: .abbreviated, time: .shortened)). The latest 200 events are kept.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .animation(.smooth, value: status.log.first?.id)
            }
        }
        .scrollIndicators(.hidden)
        .background { Backdrop(finish: finish) }
        .navigationTitle("Stock log")
        .navigationBarTitleDisplayMode(.large)
    }

    private func sightings(_ day: LogDay) -> String? {
        let count = day.entries.filter { $0.kind == .inStock }.count
        return count == 0 ? nil : count == 1 ? "1 sighting" : "\(count) sightings"
    }
}

struct LogDay: Identifiable {
    let day: Date
    /// Most recent first.
    let entries: [Status.LogEntry]
    var id: Date { day }

    var title: String {
        if Calendar.current.isDateInToday(day) { return "Today" }
        if Calendar.current.isDateInYesterday(day) { return "Yesterday" }
        return day.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }
}

extension Array where Element == Status.LogEntry {
    /// Entries most recent first, split by the day they happened on.
    var byDay: [LogDay] {
        var days: [LogDay] = []
        var current: (day: Date, entries: [Status.LogEntry])?
        for entry in self {
            let day = Calendar.current.startOfDay(for: entry.at)
            if current?.day != day {
                if let current { days.append(LogDay(day: current.day, entries: current.entries)) }
                current = (day, [])
            }
            current?.entries.append(entry)
        }
        if let current { days.append(LogDay(day: current.day, entries: current.entries)) }
        return days
    }
}

/// One line of the log. Stock coming and going stands out; the checker's own comings and goings are quieter.
private struct LogRow: View {
    let status: Status
    let entry: Status.LogEntry

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(color)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(isStock ? .subheadline.weight(.semibold) : .subheadline)
                if let detail {
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            Text(entry.at, format: .dateTime.hour().minute())
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .foregroundStyle(isStock ? .primary : .secondary)
        .accessibilityElement(children: .combine)
    }

    private var isStock: Bool { entry.kind == .inStock || entry.kind == .gone }

    private var name: String {
        entry.part.map { status.model(for: $0)?.name ?? $0 } ?? "iPhone Duo"
    }

    private var title: String {
        switch entry.kind {
        case .inStock: "\(name) in stock"
        case .gone: "\(name) gone"
        case .failing: "Apple stopped answering"
        case .recovered: "Apple answering again"
        case .stale: "Results may be stale"
        case .normal: "Results look right again"
        case .paused: "Checking paused"
        case .resumed: "Checking resumed"
        }
    }

    private var detail: String? {
        switch entry.kind {
        case .inStock: [place, entry.quote].compactMap { $0 }.joined(separator: " · ")
        case .gone: [place, entry.since.map(lasted)].compactMap { $0 }.joined(separator: " · ")
        case .failing: entry.detail
        case .stale: entry.detail.map { "iPhone 17 showed no stock near \($0)" }
        case .recovered, .normal, .paused, .resumed: nil
        }
    }

    private var symbol: String {
        switch entry.kind {
        case .inStock: "bag.fill.badge.plus"
        case .gone: "bag.badge.minus"
        case .failing: "exclamationmark.triangle.fill"
        case .recovered, .normal: "checkmark.circle"
        case .stale: "questionmark.circle.fill"
        case .paused: "pause.circle"
        case .resumed: "play.circle"
        }
    }

    private var color: Color {
        switch entry.kind {
        case .inStock: .green
        case .failing: .orange
        case .stale: .yellow
        default: .secondary
        }
    }

    /// "Apple Battersea, London", or just the store when the area is named after it.
    private var place: String? {
        guard let store = entry.storeName else { return nil }
        guard let area = entry.storeNumber.flatMap(status.area(of:)), area.name != store else { return "Apple \(store)" }
        return "Apple \(store), \(area.name)"
    }

    private func lasted(_ since: Date) -> String {
        let seconds = entry.at.timeIntervalSince(since)
        guard seconds >= 60 else { return "lasted under a minute" }
        return "lasted " + Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated, maximumUnitCount: 2))
    }
}
