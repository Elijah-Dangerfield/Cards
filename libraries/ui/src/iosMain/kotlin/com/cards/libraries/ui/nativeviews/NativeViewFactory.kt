package com.dangerfield.cards.libraries.ui.nativeviews

import androidx.compose.runtime.staticCompositionLocalOf
import platform.UIKit.UIView
import kotlin.experimental.ExperimentalObjCName

@OptIn(ExperimentalObjCName::class)
@ObjCName("CardsNativeViewFactory", exact = true)
interface NativeViewFactory {

    @Throws(Exception::class)
    fun createAppleSignInButton(
        kind: NativeAppleSignInButtonKind,
        style: NativeAppleSignInButtonStyle,
        cornerRadius: Float,
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

