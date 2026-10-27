import AlarmKit
import SwiftUI
import UserNotifications

/// Stock found in the alarm hours arrives with `alarm` set. As well as showing the notification, this rings
/// an alarm, which sounds through silent mode and Focus like the Clock app's, so a restock at 6am wakes you.
final class NotificationService: UNNotificationServiceExtension {
    private var delivery: Delivery?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        guard request.content.userInfo["alarm"] as? Bool == true,
              let content = request.content.mutableCopy() as? UNMutableNotificationContent
        else { return contentHandler(request.content) }

        let delivery = Delivery(content: content, handler: contentHandler)
        self.delivery = delivery
        let title = content.title
        Task {
            // If the alarm can't ring, the notification says why, so it's not missed silently next time.
            delivery(adding: await StockAlarm.ring(title: title))
        }
    }

    // The system gives the extension about 30 seconds. If AlarmKit hasn't answered by then, the notification
    // still has to show.
    override func serviceExtensionTimeWillExpire() {
        delivery?(adding: "No alarm: AlarmKit didn't answer in time.")
    }
}

/// The notification and the handler that shows it, taken across to the task that rings the alarm. It's
/// shown once, by whichever finishes first: the alarm or the system's deadline.
private final class Delivery: @unchecked Sendable {
    private let content: UNMutableNotificationContent
    private let handler: (UNNotificationContent) -> Void
    private let lock = NSLock()
    private var delivered = false

    init(content: UNMutableNotificationContent, handler: @escaping (UNNotificationContent) -> Void) {
        self.content = content
        self.handler = handler
    }

    func callAsFunction(adding problem: String?) {
        lock.lock()
        defer { lock.unlock() }
        guard !delivered else { return }
        delivered = true
        if let problem { content.body += "\n\(problem)" }
        handler(content)
    }
}

private enum StockAlarm {
    struct Metadata: AlarmMetadata {}

    /// Returns why the alarm didn't ring, if it didn't.
    static func ring(title: String) async -> String? {
        let manager = AlarmManager.shared
        // Not checked up front: in the extension, authorizationState can say alarms haven't been asked about
        // when the app has them allowed. Scheduling fails if they really are off, and says so.

        // One alarm at a time: more stock while it's ringing shouldn't set off another as soon as it's stopped.
        if let alarms = try? manager.alarms, alarms.contains(where: { $0.state == .alerting || $0.state == .scheduled }) { return nil }

        let attributes = AlarmAttributes<Metadata>(presentation: AlarmPresentation(alert: .init(title: "\(title)")), tintColor: .green)
        do {
            _ = try await manager.schedule(id: UUID(), configuration: .alarm(schedule: .fixed(.now.addingTimeInterval(1)), attributes: attributes))
            return nil
        } catch {
            return "No alarm: \(error.localizedDescription) (alarms \(manager.authorizationState))"
        }
    }
}
