package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.networking.NetworkInspector
import dev.skymansandy.wiretap.helper.launcher.launchWiretapConsole
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
        // `launchWiretapConsole()` is already the noop in release builds, but
        // guard on isDebug anyway: an iOS release framework built with
        // `cards.wiretap.ios` left on would link the real launcher, and this
        // keeps the inspector from ever opening there.
        if (BuildInfo.isDebug) launchWiretapConsole()
    }
}
