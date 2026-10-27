import AlarmKit
import SwiftUI
import UserNotifications

/// Stock found in the alarm hours arrives with `alarm` set. As well as showing the notification, this rings
/// an alarm, which sounds through silent mode and Focus like the Clock app's, so a restock at 6am wakes you.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        guard request.content.userInfo["alarm"] as? Bool == true,
              let content = request.content.mutableCopy() as? UNMutableNotificationContent
        else { return contentHandler(request.content) }

        let delivery = Delivery(content: content, handler: contentHandler)
        let title = content.title
        Task {
            // If the alarm can't ring, the notification says why, so it's not missed silently next time.
            if let problem = await StockAlarm.ring(title: title) { delivery.content.body += "\n\(problem)" }
            delivery()
        }
    }
}

/// The notification and the handler that shows it, taken across to the task that rings the alarm.
private struct Delivery: @unchecked Sendable {
    let content: UNMutableNotificationContent
    let handler: (UNNotificationContent) -> Void

    func callAsFunction() { handler(content) }
}

private enum StockAlarm {
    struct Metadata: AlarmMetadata {}

    /// Returns why the alarm didn't ring, if it didn't.
    static func ring(title: String) async -> String? {
        let manager = AlarmManager.shared
        switch manager.authorizationState {
        case .authorized: break
        case .denied: return "No alarm: alarms are off for Muo Duo in Settings."
        default: return "No alarm: open Muo Duo to allow alarms."
        }
        // One alarm at a time: more stock while it's ringing shouldn't set off another as soon as it's stopped.
        if let alarms = try? manager.alarms, alarms.contains(where: { $0.state == .alerting || $0.state == .scheduled }) { return nil }

        let attributes = AlarmAttributes<Metadata>(presentation: AlarmPresentation(alert: .init(title: "\(title)")), tintColor: .green)
        do {
            _ = try await manager.schedule(id: UUID(), configuration: .alarm(schedule: .fixed(.now.addingTimeInterval(1)), attributes: attributes))
            return nil
        } catch {
            return "No alarm: \(error.localizedDescription)"
        }
    }
}
