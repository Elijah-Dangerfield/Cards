import ComposeApp
import EventKit
import EventKitUI
import UIKit

class IOSCalendarLauncher: NSObject, CalendarLauncher {
    private var editorDelegate: CalendarEditorDelegate?

    func __addWeekly(event: RecurringCalendarEvent) async throws -> CalendarResult {
        return await withCheckedContinuation { continuation in
            Task { @MainActor [self] in
                guard self.editorDelegate == nil,
                      let presenter = Self.topViewController(),
                      let timeZone = TimeZone(identifier: event.timeZoneId),
                      let weekday = EKWeekday(rawValue: Int(event.isoWeekday % 7 + 1))
                else {
                    continuation.resume(returning: .unavailable)
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
                    continuation.resume(returning: result)
                }
                self.editorDelegate = delegate
                let editor = EKEventEditViewController()
                editor.eventStore = store
                editor.event = calendarEvent
                editor.editViewDelegate = delegate
                presenter.present(editor, animated: true)
                editor.presentationController?.delegate = delegate
            }
        }
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var controller = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
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

    private func finish(_ result: CalendarResult) {
        let callback = completion
        completion = nil
        callback?(result)
    }
}
