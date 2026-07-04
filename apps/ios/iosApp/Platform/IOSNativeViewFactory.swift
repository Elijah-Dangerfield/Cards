import SwiftUI
import UIKit
import AuthenticationServices
import ComposeApp

final class IOSNativeViewFactory: VirtuNativeViewFactory {

    static let shared = IOSNativeViewFactory()
    private let decoder = JSONDecoder()
    private let logger = KLog.shared.withTag(tag:"NativeViewFactory")

    func createAppleSignInButton(
        kind: VirtuNativeAppleSignInButtonKind,
        style: VirtuNativeAppleSignInButtonStyle,
        cornerRadius: Float,
        onTap: @escaping () -> Void
    ) throws -> UIView {
        AppleSignInButtonHost(
            type: kind.buttonType,
            style: style.buttonStyle,
            cornerRadius: CGFloat(cornerRadius),
            action: onTap
        )
    }

    func updateAppleSignInButton(
        view: UIView,
        enabled: Bool,
        onTap: @escaping () -> Void
    ) {
        guard let host = view as? AppleSignInButtonHost else {
            logger.e { "Attempted to update unexpected Apple button view type: \(String(describing: type(of: view)))" }
            return
        }

        host.updateAction(onTap)
        host.setEnabled(enabled)
    }
}

@objcMembers
final class AppleSignInButtonHost: UIView {
    private var action: (() -> Void)
    private let button: ASAuthorizationAppleIDButton

    init(
        type: ASAuthorizationAppleIDButton.ButtonType,
        style: ASAuthorizationAppleIDButton.Style,
        cornerRadius: CGFloat,
        action: @escaping () -> Void
    ) {
        self.action = action
        self.button = ASAuthorizationAppleIDButton(type: type, style: style)
        super.init(frame: .zero)

        button.translatesAutoresizingMaskIntoConstraints = false
        button.cornerRadius = cornerRadius
        button.addTarget(self, action: #selector(handleTap), for: .touchUpInside)

        addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: leadingAnchor),
            button.trailingAnchor.constraint(equalTo: trailingAnchor),
            button.topAnchor.constraint(equalTo: topAnchor),
            button.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    @objc private func handleTap() {
        action()
    }

    func updateAction(_ newAction: @escaping () -> Void) {
        action = newAction
    }

    func setEnabled(_ enabled: Bool) {
        button.isEnabled = enabled
        alpha = enabled ? 1.0 : 0.6
    }
}

private extension VirtuNativeAppleSignInButtonKind {
    var buttonType: ASAuthorizationAppleIDButton.ButtonType {
        switch self {
        case .signIn:
            return .signIn
        case .continueFlow:
            return .continue
        }
    }
}

private extension VirtuNativeAppleSignInButtonStyle {
    var buttonStyle: ASAuthorizationAppleIDButton.Style {
        switch self {
        case .black:
            return .black
        case .white:
            return .white
        case .whiteOutline:
            return .whiteOutline
        }
    }
}
