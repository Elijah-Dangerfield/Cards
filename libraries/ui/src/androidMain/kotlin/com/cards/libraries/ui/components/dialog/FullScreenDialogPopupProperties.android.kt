package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.ui.window.PopupProperties

/**
 * Android popups already fill the window (and draw under system bars in an
 * edge-to-edge app), so there's no `usePlatformInsets` to opt out of here.
 */
internal actual fun fullScreenDialogPopupProperties(): PopupProperties = PopupProperties(
    focusable = true,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    clippingEnabled = false,
)
