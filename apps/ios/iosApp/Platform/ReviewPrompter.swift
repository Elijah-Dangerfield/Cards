//
//  ReviewPrompter.swift
//  iosApp
//
//  Wraps SKStoreReviewController. The OS owns the throttling decision —
//  this returns immediately whether or not the dialog actually showed.
//
import ComposeApp
import StoreKit
import UIKit

class IOSReviewPrompter: ReviewPrompter {

    func __requestReview() async throws {
        await MainActor.run {
            guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
                ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first
            else { return }

            if #available(iOS 14.0, *) {
                SKStoreReviewController.requestReview(in: scene)
            } else {
                SKStoreReviewController.requestReview()
            }
        }
    }
}
