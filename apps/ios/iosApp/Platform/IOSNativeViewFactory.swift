import SwiftUI
import UIKit
import AuthenticationServices
import ComposeApp

final class IOSNativeViewFactory: CardsNativeViewFactory {

    static let shared = IOSNativeViewFactory()
    private let decoder = JSONDecoder()
    private let logger = KLog.shared.withTag(tag:"NativeViewFactory")

    func createAppleSignInButton(
        kind: CardsNativeAppleSignInButtonKind,
        style: CardsNativeAppleSignInButtonStyle,
        cornerRadius: Float,
        backgroundColor: UIColor,
        onTap: @escaping () -> Void
    ) throws -> UIView {
        AppleSignInButtonHost(
            type: kind.buttonType,
            style: style.buttonStyle,
            cornerRadius: CGFloat(cornerRadius),
            backgroundColor: backgroundColor,
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
    private let cornerRadius: CGFloat

    init(
        type: ASAuthorizationAppleIDButton.ButtonType,
        style: ASAuthorizationAppleIDButton.Style,
        cornerRadius: CGFloat,
        backgroundColor: UIColor,
        action: @escaping () -> Void
    ) {
        self.action = action
        self.cornerRadius = cornerRadius
        self.button = ASAuthorizationAppleIDButton(type: type, style: style)
        super.init(frame: .zero)

        // The button draws a correctly-rounded pill, but its view bounds are a
        // square, and this host is the opaque rectangle Compose punches out of
        // the Skia canvas. Painting the host the page's background color makes
        // the square corners around the pill blend into the page, so the
        // rounding reads correctly. (Clipping the interop layer doesn't work —
        // the opaque fill is Compose-side; matching its color is what does.)
        self.backgroundColor = backgroundColor
        isOpaque = true

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

    // ASAuthorizationAppleIDButton is created at `.zero` then resized by Auto
    // Layout; re-applying the radius after each layout pass keeps the pill
    // rounded at its real size.
    override func layoutSubviews() {
        super.layoutSubviews()
        button.cornerRadius = cornerRadius
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

private extension CardsNativeAppleSignInButtonKind {
    var buttonType: ASAuthorizationAppleIDButton.ButtonType {
        switch self {
        case .signIn:
            return .signIn
        case .continueFlow:
            return .continue
        }
    }
}

private extension CardsNativeAppleSignInButtonStyle {
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
