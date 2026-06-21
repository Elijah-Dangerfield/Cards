package com.dangerfield.cards.libraries.networking.impl

import dev.skymansandy.wiretap.helper.launcher.WiretapViewController
import dev.skymansandy.wiretap.plugin.http.WiretapKtorHttpPlugin
import io.ktor.client.HttpClientConfig
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Presents the Wiretap console as a page sheet rather than the library's
 * hardcoded full-screen modal. A page sheet has a built-in swipe-down-to-
 * dismiss affordance, which the console's root screen otherwise lacks on iOS
 * (no close button, and a full-screen modal has no interactive dismiss) —
 * see [launchNetworkInspector]'s expect doc.
 */
internal actual fun launchNetworkInspector() {
    val keyWindow = UIApplication.sharedApplication.connectedScenes
        .asSequence()
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>().asSequence() }
        .firstOrNull { it.isKeyWindow() }
        ?: return

    var top: UIViewController? = keyWindow.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    val host = top ?: return

    val consoleVc = WiretapViewController()
    consoleVc.setModalPresentationStyle(UIModalPresentationPageSheet)
    host.presentViewController(consoleVc, animated = true, completion = null)
}

internal actual fun HttpClientConfig<*>.installNetworkInspector() {
    install(WiretapKtorHttpPlugin)
}
