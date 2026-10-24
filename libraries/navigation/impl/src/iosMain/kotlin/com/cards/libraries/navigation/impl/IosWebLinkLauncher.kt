package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hands a URL off to whichever app owns its scheme, and falls back to an in-app
 * browser when the system declines a web page.
 *
 * Deliberately does **not** gate on `canOpenURL`. Since iOS 9 that call only
 * answers truthfully for schemes declared in `LSApplicationQueriesSchemes`, and
 * http/https lose their built-in exemption as soon as the user picks a
 * third-party default browser. It then reports "no handler" for links the
 * system would have opened perfectly well — which is how every outbound link
 * (Terms, Privacy, Support, store listing) silently died on those devices.
 *
 * `openURL` can still refuse, because it needs some *other* app to accept the
 * URL: a restricted device or a wedged default browser turns the tap into
 * nothing at all (CARDS-CY, the problem-gambling resource). An http(s) URL has
 * a second route that needs no other app, so a refusal falls back to
 * `SFSafariViewController`. `openURL` stays first: it is what sends an
 * apps.apple.com link to the App Store app rather than to a web view of the
 * listing, and what lets `cards://` and `mailto:` reach their handlers.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosWebLinkLauncher @Inject constructor() : WebLinkLauncher {

    private val logger = KLog.withTag("IosWebLinkLauncher")

    override fun open(url: String): Catching<Unit> = open(url, SystemUrlOpener, SafariPagePresenter)

    internal fun open(
        url: String,
        opener: UrlOpener,
        presenter: WebPagePresenter,
    ): Catching<Unit> = Catching {
        val targetUrl = requireNotNull(NSURL.URLWithString(url)) {
            "Invalid url: $url"
        }
        opener.open(targetUrl) { opened ->
            // Fires long after the Catching above has returned, and across an
            // ObjC block boundary, so nothing in here may throw.
            if (!opened) onRefusal(url, targetUrl, presenter)
        }
    }

    private fun onRefusal(url: String, targetUrl: NSURL, presenter: WebPagePresenter) {
        if (!targetUrl.isWebPage()) {
            // A scheme only another app can service. Nothing left to try, and
            // the OS declining is not a bug in us, so this stays below Error.
            logger.w("iOS declined to open $url, which is not a web page we can show in-app")
            return
        }

        Catching { presenter.present(targetUrl) }
            .onSuccess { presented ->
                if (presented) {
                    logger.w("iOS declined to open $url, showing it in an in-app browser instead")
                } else {
                    logger.e("iOS declined to open $url and there was no view controller to show it from")
                }
            }
            .onFailure { logger.e(it, "iOS declined to open $url and the in-app browser failed too") }
    }
}

private fun NSURL.isWebPage(): Boolean = scheme?.lowercase() in WEB_SCHEMES

private val WEB_SCHEMES = setOf("http", "https")

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

/** Shows a web page without needing another app to accept it. */
internal fun interface WebPagePresenter {
    fun present(url: NSURL): Boolean
}

internal val SafariPagePresenter = WebPagePresenter { url ->
    val host = topViewController()
    host?.presentViewController(
        SFSafariViewController(uRL = url),
        animated = true,
        completion = null,
    )
    host != null
}
