package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.ui.window.PopupProperties

/**
 * Desktop (skiko) mirrors iOS: opt out of platform-inset padding so the scrim
 * fills the whole window. Desktop is a dev/preview surface for this app, but
 * keeping parity avoids a scrim that stops short of the window edges.
 */
internal actual fun fullScreenDialogPopupProperties(): PopupProperties = PopupProperties(
    focusable = true,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    clippingEnabled = false,
    usePlatformInsets = false,
)
