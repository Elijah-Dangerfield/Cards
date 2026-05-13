package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Placeholder NetworkConfig so the module is self-contained. Override by
 * binding your own `NetworkConfig` with `replaces = [DefaultNetworkConfig::class]`
 * — typically reading the base URL out of BuildConfig per build variant.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = ""
}
