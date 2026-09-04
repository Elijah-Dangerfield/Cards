package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hands a URL off to whichever app owns its scheme.
 *
 * Deliberately does **not** gate on `canOpenURL`. Since iOS 9 that call only
 * answers truthfully for schemes declared in `LSApplicationQueriesSchemes`, and
 * http/https lose their built-in exemption as soon as the user picks a
 * third-party default browser. It then reports "no handler" for links the
 * system would have opened perfectly well — which is how every outbound link
 * (Terms, Privacy, Support, store listing) silently died on those devices.
 *
 * The whitelist never applied to `openURL` itself, so open unconditionally and
 * let the completion handler report what actually happened.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosWebLinkLauncher @Inject constructor() : WebLinkLauncher {

    private val logger = KLog.withTag("IosWebLinkLauncher")

    override fun open(url: String): Catching<Unit> = open(url, SystemUrlOpener)

    internal fun open(url: String, opener: UrlOpener): Catching<Unit> = Catching {
        val targetUrl = requireNotNull(NSURL.URLWithString(url)) {
            "Invalid url: $url"
        }
        opener.open(targetUrl) { opened ->
            // `openURL` is async — this fires long after the Catching above has
            // returned success, so a refusal can only be reported, not thrown.
            if (!opened) logger.e("iOS refused to open $url")
        }
    }
}

/**
 * The sliver of `UIApplication` [IosWebLinkLauncher] depends on, so the open
 * path can be exercised without a running app.
 */
internal fun interface UrlOpener {
    fun open(url: NSURL, onResult: (opened: Boolean) -> Unit)
}

internal val SystemUrlOpener = UrlOpener { url, onResult ->
    UIApplication.sharedApplication.openURL(
        url = url,
        options = emptyMap<Any?, Any?>(),
        completionHandler = { opened -> onResult(opened) },
    )
}
