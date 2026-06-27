package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.navigation.ShareLauncher
import me.tatarka.inject.annotations.Inject
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosShareLauncher @Inject constructor() : ShareLauncher {

    override fun share(text: String): Catching<Unit> = Catching {
        val host = topViewController() ?: error("No view controller to present the share sheet from")
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        controller.popoverPresentationController?.sourceView = host.view
        host.presentViewController(controller, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
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
}
