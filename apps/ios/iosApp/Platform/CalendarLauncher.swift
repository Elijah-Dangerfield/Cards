import ComposeApp
import EventKit
import EventKitUI
import UIKit

class IOSCalendarLauncher: NSObject, CalendarLauncher {
    private var editorDelegate: CalendarEditorDelegate?

    func __addWeekly(event: RecurringCalendarEvent) async throws -> CalendarResult {
        return await withCheckedContinuation { continuation in
            Task { @MainActor [self] in
                self.presentEditor(for: event) { continuation.resume(returning: $0) }
            }
        }
    }

    @MainActor
    private func presentEditor(for event: RecurringCalendarEvent, completion: @escaping (CalendarResult) -> Void) {
        guard editorDelegate == nil,
              let presenter = Self.presenter(),
              let timeZone = TimeZone(identifier: event.timeZoneId),
              let weekday = EKWeekday(rawValue: Int(event.isoWeekday % 7 + 1))
        else {
            completion(.unavailable)
            return
        }

        let store = EKEventStore()
        let calendarEvent = EKEvent(eventStore: store)
        calendarEvent.title = event.title
        calendarEvent.notes = event.notes
        calendarEvent.url = URL(string: event.url)
        calendarEvent.startDate = Date(timeIntervalSince1970: Double(event.startEpochMilliseconds) / 1000)
        calendarEvent.endDate = calendarEvent.startDate.addingTimeInterval(Double(event.durationMinutes) * 60)
        calendarEvent.timeZone = timeZone
        calendarEvent.addRecurrenceRule(EKRecurrenceRule(
            recurrenceWith: .weekly,
            interval: 1,
            daysOfTheWeek: [EKRecurrenceDayOfWeek(weekday)],
            daysOfTheMonth: nil,
            monthsOfTheYear: nil,
            weeksOfTheYear: nil,
            daysOfTheYear: nil,
            setPositions: nil,
            end: nil
        ))
        calendarEvent.addAlarm(EKAlarm(relativeOffset: -Double(event.alertMinutesBefore) * 60))

        let delegate = CalendarEditorDelegate { [weak self] result in
            self?.editorDelegate = nil
            completion(result)
        }
        editorDelegate = delegate
        let editor = EKEventEditViewController()
        editor.eventStore = store
        editor.event = calendarEvent
        editor.editViewDelegate = delegate
        // UIKit drops a presentation it can't perform without calling any delegate,
        // and sometimes without calling this completion either, so check both times.
        presenter.present(editor, animated: true) {
            if editor.presentingViewController == nil { delegate.finish(.unavailable) }
        }
        guard editor.presentingViewController != nil else {
            delegate.finish(.unavailable)
            return
        }
        editor.presentationController?.delegate = delegate
    }

    @MainActor
    private static func presenter() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var controller = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        while let dismissing = controller, dismissing.isBeingDismissed {
            controller = dismissing.presentingViewController
        }
        return controller
    }
}

@MainActor
private final class CalendarEditorDelegate: NSObject, @preconcurrency EKEventEditViewDelegate, UIAdaptivePresentationControllerDelegate {
    private var completion: ((CalendarResult) -> Void)?

    init(completion: @escaping (CalendarResult) -> Void) {
        self.completion = completion
    }

    func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
        let result: CalendarResult = action == .saved ? .added : .cancelled
        controller.dismiss(animated: true) {
            self.finish(result)
        }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        finish(.cancelled)
    }

    func finish(_ result: CalendarResult) {
        let callback = completion
        completion = nil
        callback?(result)
    }
}
