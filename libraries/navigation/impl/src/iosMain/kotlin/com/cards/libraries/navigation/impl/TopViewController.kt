package com.dangerfield.cards.libraries.navigation.impl

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * The frontmost view controller, for presenting UIKit over the Compose host.
 */
internal fun topViewController(): UIViewController? {
    val keyWindow = UIApplication.sharedApplication.connectedScenes
        .asSequence()
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>().asSequence() }
        .firstOrNull { it.isKeyWindow() }
        ?: return null

    var top: UIViewController? = keyWindow.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}
