import AuthenticationServices
import ComposeApp
import CryptoKit
import Security
import UIKit

/// Swift implementation of the Kotlin `AppleSignInCoordinator` protocol
/// (SKIE-exported as `IdentityAppleSignInCoordinator`). Runs the native
/// `ASAuthorizationController` flow, hashes a nonce for Apple, and hands the
/// id token + the *raw* nonce back to Kotlin for the Supabase
/// `signInWith(IDToken)` exchange.
///
/// Plain callback (no async) to match the non-suspend Kotlin interface:
///  - `onComplete(credential, nil)` on success,
///  - `onComplete(nil, nil)` if the user dismissed the sheet,
///  - `onComplete(nil, message)` on a genuine failure.
class IOSAppleSignInCoordinator: NSObject, IdentityAppleSignInCoordinator {

    // Retained for the lifetime of the flow — ARC would otherwise dealloc the
    // delegate mid-authorization and the callback would never fire.
    private var delegate: AppleSignInDelegate?

    func requestCredential(
        onComplete: @escaping (IdentityAppleSignInCredential?, String?) -> Void
    ) {
        let rawNonce = IOSAppleSignInCoordinator.randomNonce()
        let hashedNonce = IOSAppleSignInCoordinator.sha256(rawNonce)

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = hashedNonce // SHA-256 hash goes to Apple

        let controller = ASAuthorizationController(authorizationRequests: [request])
        let delegate = AppleSignInDelegate(
            rawNonce: rawNonce, // raw nonce travels back for Supabase
            onComplete: onComplete,
            onFinish: { [weak self] in self?.delegate = nil }
        )

        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        delegate.controller = controller
        self.delegate = delegate
        controller.performRequests()
    }

    private static func randomNonce(length: Int = 32) -> String {
        precondition(length > 0)
        let charset: [Character] =
            Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remainingLength = length

        while remainingLength > 0 {
            let randoms: [UInt8] = (0..<16).map { _ in
                var random: UInt8 = 0
                let errorCode = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
                if errorCode != errSecSuccess {
                    fatalError("Unable to generate nonce. SecRandomCopyBytes failed: \(errorCode)")
                }
                return random
            }

            randoms.forEach { random in
                if remainingLength == 0 { return }
                if random < charset.count {
                    result.append(charset[Int(random)])
                    remainingLength -= 1
                }
            }
        }

        return result
    }

    private static func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }
}

private final class AppleSignInDelegate: NSObject, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {

    var controller: ASAuthorizationController?
    private let rawNonce: String
    private var onComplete: ((IdentityAppleSignInCredential?, String?) -> Void)?
    private let onFinish: () -> Void

    init(
        rawNonce: String,
        onComplete: @escaping (IdentityAppleSignInCredential?, String?) -> Void,
        onFinish: @escaping () -> Void
    ) {
        self.rawNonce = rawNonce
        self.onComplete = onComplete
        self.onFinish = onFinish
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        if let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) {
            return window
        }
        return UIApplication.shared.windows.first ?? UIWindow()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        defer { cleanup() }

        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            onComplete?(nil, "Missing Apple credential")
            return
        }

        guard let tokenData = credential.identityToken,
              let identityToken = String(data: tokenData, encoding: .utf8) else {
            onComplete?(nil, "Unable to decode Apple identity token")
            return
        }

        let authCode = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        let givenName = credential.fullName?.givenName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let familyName = credential.fullName?.familyName?.trimmingCharacters(in: .whitespacesAndNewlines)

        let result = IdentityAppleSignInCredential(
            identityToken: identityToken,
            nonce: rawNonce,
            authorizationCode: authCode,
            givenName: (givenName?.isEmpty ?? true) ? nil : givenName,
            familyName: (familyName?.isEmpty ?? true) ? nil : familyName
        )
        onComplete?(result, nil)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        defer { cleanup() }

        // User dismissed the sheet → (nil, nil): a quiet no-op on the Kotlin side.
        if let authError = error as? ASAuthorizationError, authError.code == .canceled {
            onComplete?(nil, nil)
            return
        }
        onComplete?(nil, error.localizedDescription)
    }

    private func cleanup() {
        onComplete = nil
        controller = nil
        onFinish()
    }
}
