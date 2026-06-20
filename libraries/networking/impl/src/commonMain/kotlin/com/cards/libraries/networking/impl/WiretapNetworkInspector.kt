package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.networking.NetworkInspector
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * [NetworkInspector] backed by WiretapKMP's programmatic launcher. The
 * launcher comes transitively with the Wiretap Ktor artifact, so capture
 * (the installed plugin) and launch share the same dependency + variant
 * swap.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WiretapNetworkInspector : NetworkInspector {
    override fun open() {
        // The launcher is already the noop in release builds, but guard on
        // isDebug anyway: an iOS release framework built with
        // `cards.wiretap.ios` left on would link the real launcher, and this
        // keeps the inspector from ever opening there.
        if (BuildInfo.isDebug) launchNetworkInspector()
    }
}

/**
 * Opens the Wiretap console. Per-platform because the library's
 * `launchWiretapConsole()` presents differently:
 *
 *  - **Android** uses it as-is — a full-screen Activity whose root maps the
 *    system back gesture to `finish()`, so there's always a way out.
 *  - **iOS** does NOT use it: that path presents a `UIModalPresentationFullScreen`
 *    modal, and the console's root screen has no close button and a full-screen
 *    modal has no swipe-to-dismiss — so you get trapped. The iOS actual instead
 *    presents the console as a page sheet (swipe down to dismiss).
 */
internal expect fun launchNetworkInspector()
