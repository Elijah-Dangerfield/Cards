package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.ui.window.PopupProperties

/**
 * Opt out of platform-inset padding so the popup — and the dialog scrim inside
 * it — reaches under the status bar and home indicator to cover the full screen.
 * Without this the scrim stops at the safe area and reads as a floating panel
 * rather than a full-screen dim (CARDS-78).
 */
internal actual fun fullScreenDialogPopupProperties(): PopupProperties = PopupProperties(
    focusable = true,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    clippingEnabled = false,
    usePlatformInsets = false,
)
