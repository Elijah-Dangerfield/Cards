package com.dangerfield.cards.server.di

import com.dangerfield.cards.server.domain.AppConfigSource
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Root DI component for the server process. Mirrors the client's `AppComponent` —
 * anvil aggregates every `@ContributesBinding(ServerScope::class)` on the classpath
 * and KSP generates the merged implementation at compile time.
 *
 * Boot the component once at startup:
 *
 * ```
 * val component = ServerComponent::class.create()
 * routing { appConfig(component.appConfigSource) }
 * ```
 *
 * Add a new service: annotate its impl with `@ContributesBinding(ServerScope::class)`,
 * expose its interface as a property here, done. No manual module wiring.
 */
@MergeComponent(ServerScope::class)
@SingleIn(ServerScope::class)
abstract class ServerComponent {
    abstract val appConfigSource: AppConfigSource
}
