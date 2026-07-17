package com.dangerfield.cards.libraries.ui.nativeviews

import androidx.compose.runtime.staticCompositionLocalOf
import platform.UIKit.UIColor
import platform.UIKit.UIView
import kotlin.experimental.ExperimentalObjCName

@OptIn(ExperimentalObjCName::class)
@ObjCName("CardsNativeViewFactory", exact = true)
interface NativeViewFactory {

    /**
     * [backgroundColor] is the color of the surface the button sits on, passed
     * from Compose. A `UIKitView` punches an opaque rectangle out of the Skia
     * canvas; the native button rounds its own pill but its square view bounds
     * would otherwise reveal that opaque fill at the corners. Painting the host
     * this same background color makes the corners blend into the page so the
     * rounded pill reads correctly — the standing convention for every native
     * interop view here.
     */
    @Throws(Exception::class)
    fun createAppleSignInButton(
        kind: NativeAppleSignInButtonKind,
        style: NativeAppleSignInButtonStyle,
        cornerRadius: Float,
        backgroundColor: UIColor,
        onTap: () -> Unit
    ): UIView

    fun updateAppleSignInButton(
        view: UIView,
        enabled: Boolean,
        onTap: () -> Unit
    )
}

val LocalNativeViewFactory = staticCompositionLocalOf<NativeViewFactory?> { null }

@OptIn(ExperimentalObjCName::class)
@ObjCName("CardsNativeAppleSignInButtonKind", exact = true)
enum class NativeAppleSignInButtonKind {
    SignIn,
    ContinueFlow
}

@OptIn(ExperimentalObjCName::class)
@ObjCName("CardsNativeAppleSignInButtonStyle", exact = true)
enum class NativeAppleSignInButtonStyle {
    Black,
    White,
    WhiteOutline
}

