package com.dangerfield.cards.libraries.ui.nativeviews

import androidx.compose.runtime.staticCompositionLocalOf
import platform.UIKit.UIView
import kotlin.experimental.ExperimentalObjCName

/**
 * Camera guidance state from the native camera preview.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("VirtuCameraGuidanceState", exact = true)
enum class CameraGuidanceState {
    Ready,           // Good to capture
    TiltedTooMuch,   // Phone is tilted, suggest holding flat
    TooDark,         // Low light, suggest using flash
    TooBlurry        // Motion detected, hold steady
}

@OptIn(ExperimentalObjCName::class)
@ObjCName("VirtuNativeViewFactory", exact = true)
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

    @Throws(Exception::class)
    fun createCameraPreview(): UIView

    fun startCameraPreview(view: UIView)

    fun stopCameraPreview(view: UIView)

    fun capturePhoto(view: UIView, onCaptured: (ByteArray?) -> Unit)
    
    /**
     * Toggle flash/torch on the camera preview.
     * @return true if flash is now enabled, false if disabled
     */
    fun toggleCameraFlash(view: UIView): Boolean
    
    /**
     * Check if flash is currently enabled.
     */
    fun isCameraFlashEnabled(view: UIView): Boolean
}

val LocalNativeViewFactory = staticCompositionLocalOf<NativeViewFactory?> { null }

@OptIn(ExperimentalObjCName::class)
@ObjCName("VirtuNativeAppleSignInButtonKind", exact = true)
enum class NativeAppleSignInButtonKind {
    SignIn,
    ContinueFlow
}

@OptIn(ExperimentalObjCName::class)
@ObjCName("VirtuNativeAppleSignInButtonStyle", exact = true)
enum class NativeAppleSignInButtonStyle {
    Black,
    White,
    WhiteOutline
}

