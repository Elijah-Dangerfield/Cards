package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosWebLinkLauncher @Inject constructor() : WebLinkLauncher {

    override fun open(url: String): Catching<Unit> = Catching {
        val targetUrl = requireNotNull(NSURL.URLWithString(url)) {
            "Invalid url: $url"
        }
        val application = UIApplication.sharedApplication
        check(application.canOpenURL(targetUrl)) { "No handler available for $url" }
        application.openURL(
            url = targetUrl,
            options = emptyMap<Any?, Any?>(),
            completionHandler = null,
        )
    }
}
