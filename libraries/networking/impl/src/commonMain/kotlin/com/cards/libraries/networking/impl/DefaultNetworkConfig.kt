package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default network config. Points at the deployed dev server on Fly so real
 * devices and sims share the same backend. For local backend work, replace
 * the binding with one returning `http://10.0.2.2:8080` (Android emulator)
 * or `http://localhost:8080` (iOS sim) and start the server with
 * `./gradlew :apps:server:run`. Prod gets its own NetworkConfig via build
 * variant once we have proper release channels.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = "https://cards-server-dev.fly.dev"
}
