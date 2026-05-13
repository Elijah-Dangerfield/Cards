//
//  PermissionManager.swift
//  iosApp
//
//  Scaffolding for iOS permission handling. The template only ships a
//  notifications implementation; add more cases to `Permission`
//  (commonMain) and handle them below as your project needs them.
//
import ComposeApp
import Foundation
import UserNotifications
import UIKit

class IOSPermissionManager: PermissionManager {

    func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    func __ensurePermission(permission: Permission) async throws -> PermissionResult {
        let currentStatus = checkPermissionStatus(permission: permission)

        if currentStatus == .granted {
            return PermissionResultGranted.shared
        }

        return try await __requestPermission(permission: permission)
    }

    func __requestPermission(permission: Permission) async throws -> PermissionResult {
        switch onEnum(of: permission) {
        case .notifications:
            return await requestNotificationsPermission()
        }
    }

    func checkPermissionStatus(permission: Permission) -> PermissionStatus {
        switch onEnum(of: permission) {
        case .notifications:
            return checkNotificationsStatus()
        }
    }

    // MARK: - Notifications

    private func checkNotificationsStatus() -> PermissionStatus {
        var status: PermissionStatus = .notDetermined
        let semaphore = DispatchSemaphore(value: 0)

        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .notDetermined:
                status = .notDetermined
            case .authorized, .provisional, .ephemeral:
                status = .granted
            case .denied:
                status = .denied
            @unknown default:
                status = .notDetermined
            }
            semaphore.signal()
        }

        semaphore.wait()
        return status
    }

    private func requestNotificationsPermission() async -> PermissionResult {
        do {
            let granted = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .badge, .sound])

            if granted {
                return PermissionResultGranted.shared
            } else {
                let settings = await UNUserNotificationCenter.current().notificationSettings()
                let canRequestAgain = settings.authorizationStatus == .notDetermined
                return PermissionResultDenied(canRequestAgain: canRequestAgain)
            }
        } catch {
            return PermissionResultDenied(canRequestAgain: true)
        }
    }
}
