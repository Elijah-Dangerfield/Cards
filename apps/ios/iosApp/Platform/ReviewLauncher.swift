//
//  ReviewLauncher.swift
//  iosApp
//
//  Wraps SKStoreReviewController. The OS owns the throttling decision, so
//  this returns immediately whether or not the dialog actually showed. With
//  no window scene there is nothing to show it in: reporting that back lets
//  the coordinator keep the ask for a better moment instead of spending it.
//
import ComposeApp
import StoreKit
import UIKit

class IOSReviewLauncher: ReviewLauncher {

    func __requestReview() async throws -> ReviewLaunchOutcome {
        return await MainActor.run {
            guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
                ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first
            else { return .noForegroundUi }

            if #available(iOS 14.0, *) {
                SKStoreReviewController.requestReview(in: scene)
            } else {
                SKStoreReviewController.requestReview()
            }
            return .requested
        }
    }
}
