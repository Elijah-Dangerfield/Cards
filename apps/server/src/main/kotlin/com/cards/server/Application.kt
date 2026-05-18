package com.dangerfield.cards.server

import com.dangerfield.cards.server.di.ServerComponent
import com.dangerfield.cards.server.di.create
import com.dangerfield.cards.server.plugins.installCors
import com.dangerfield.cards.server.plugins.installObservability
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.routes.appConfigRoutes
import com.dangerfield.cards.server.routes.healthRoutes
import com.dangerfield.cards.server.routes.inventoryRoutes
import com.dangerfield.cards.server.routes.productsRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * Single source of truth for how the app boots. Stays small on purpose — the
 * plugins/ and routes/ packages own their own concerns and this just wires
 * them together in the right order.
 *
 * Order matters for plugins: serialization before status pages (so error
 * envelopes can be encoded), CORS before anything else, observability anywhere.
 */
fun Application.module() {
    installSerialization()
    installCors()
    installObservability()
    installStatusPages()

    val component = ServerComponent::class.create()

    routing {
        healthRoutes()
        appConfigRoutes(component.appConfigSource)
        productsRoutes(component.productCatalogSource)
        inventoryRoutes()
    }
}
