package com.dangerfield.cards.server

import com.dangerfield.cards.server.config.ServerConfig
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerComponent
import com.dangerfield.cards.server.di.create
import com.dangerfield.cards.server.plugins.installAuthentication
import com.dangerfield.cards.server.plugins.installCors
import com.dangerfield.cards.server.plugins.installObservability
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.routes.appConfigRoutes
import com.dangerfield.cards.server.routes.avatarRoutes
import com.dangerfield.cards.server.routes.healthRoutes
import com.dangerfield.cards.server.routes.inventoryRoutes
import com.dangerfield.cards.server.routes.meRoutes
import com.dangerfield.cards.server.routes.productsRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * Single source of truth for how the app boots. Stays small on purpose —
 * the plugins/ and routes/ packages own their own concerns and this just
 * wires them together in the right order.
 *
 * Order matters: serialization before status pages (so error envelopes can
 * be encoded), auth after serialization (the JWT challenge writes a JSON
 * body), CORS before anything else, observability anywhere.
 */
fun Application.module(config: ServerConfig) {
    val logger = LoggerFactory.getLogger("Bootstrap")
    logger.info("Booting cards-server on ${config.http.host}:${config.http.port}")

    installSerialization()
    installCors()
    installObservability()
    installStatusPages()
    installAuthentication(config.supabase)

    val database = Database.connect(config.database)
    logger.info("Database connected and migrations applied")

    val component = ServerComponent::class.create(database, config.supabase)

    routing {
        healthRoutes()
        appConfigRoutes(component.appConfigSource)
        productsRoutes(component.productCatalogSource)
        inventoryRoutes()
        meRoutes(component.profileRepository, component.supabaseAdminClient)
        avatarRoutes()
    }
}

/**
 * Test-friendly entry point that boots routes without touching the
 * database or auth. The integration-test pattern is to call this overload
 * OR to mount specific routes directly inside `testApplication { }`,
 * depending on the test's scope.
 */
fun Application.module() {
    installSerialization()
    installCors()
    installObservability()
    installStatusPages()

    routing {
        healthRoutes()
    }
}
