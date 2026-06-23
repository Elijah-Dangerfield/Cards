package com.dangerfield.cards.server

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.config.ServerConfig
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerComponent
import com.dangerfield.cards.server.di.create
import com.dangerfield.cards.server.plugins.JwtVerification
import com.dangerfield.cards.server.plugins.installAuthentication
import com.dangerfield.cards.server.plugins.installCors
import com.dangerfield.cards.server.plugins.installHttpServerTracing
import com.dangerfield.cards.server.plugins.installObservability
import com.dangerfield.cards.server.plugins.installOpenTelemetry
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSentry
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.plugins.installWebSockets
import com.dangerfield.cards.server.routes.achievementsRoutes
import com.dangerfield.cards.server.routes.adminRoutes
import com.dangerfield.cards.server.routes.appConfigRoutes
import com.dangerfield.cards.server.routes.avatarRoutes
import com.dangerfield.cards.server.routes.equipmentRoutes
import com.dangerfield.cards.server.routes.friendsRoutes
import com.dangerfield.cards.server.routes.grantsRoutes
import com.dangerfield.cards.server.routes.healthRoutes
import com.dangerfield.cards.server.routes.inventoryRoutes
import com.dangerfield.cards.server.routes.meRoutes
import com.dangerfield.cards.server.routes.messageRoutes
import com.dangerfield.cards.server.routes.productsRoutes
import com.dangerfield.cards.server.routes.profilesRoutes
import com.dangerfield.cards.server.routes.progressionRoutes
import com.dangerfield.cards.server.routes.recentOpponentsRoutes
import com.dangerfield.cards.server.routes.matchmakingRoutes
import com.dangerfield.cards.server.routes.roomRoutes
import com.dangerfield.cards.server.routes.roomSocketRoutes
import com.dangerfield.cards.server.routes.walletRoutes
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

    // Production-only observability — kept out of [installApp] so tests don't
    // pay for Sentry/OTel. Sentry first so any later boot failure is captured;
    // OpenTelemetry + HTTP tracing next so subsequent plugins' spans export.
    installSentry(config.sentry)
    val openTelemetry = installOpenTelemetry(config.observability)
    installHttpServerTracing(openTelemetry)
    installObservability()

    val database = Database.connect(config.database)
    logger.info("Database connected and migrations applied")

    val component = ServerComponent::class.create(database, config.supabase)

    installApp(
        component = component,
        verification = JwtVerification.Jwks(config.supabase.jwksUrl, config.supabase.expectedIssuer),
        adminConfig = config.admin,
    )
}

/**
 * Installs the functional plugins + every application route for an
 * already-constructed [component] and JWT [verification] strategy.
 *
 * This is the seam shared by production [module] and full-stack integration
 * tests: a test builds a [ServerComponent] against a Testcontainers database,
 * passes a [JwtVerification.Static] verifier, and exercises the **real DI graph
 * + real routes + real DB** without the production-only observability plugins or
 * the live Supabase JWKS fetch.
 *
 * Order matters: serialization before status pages (so error envelopes encode),
 * auth after serialization (the JWT challenge writes a JSON body), CORS early.
 */
fun Application.installApp(
    component: ServerComponent,
    verification: JwtVerification,
    adminConfig: AdminConfig,
) {
    installSerialization()
    installCors()
    installRateLimits()
    installWebSockets()
    installStatusPages()
    installAuthentication(verification)

    routing {
        healthRoutes()
        appConfigRoutes(component.appConfigSource)
        productsRoutes(component.productCatalogSource)
        inventoryRoutes(component.inventoryRepository)
        walletRoutes(
            repository = component.walletRepository,
            messages = component.userMessageRepository,
            clock = component.provideClock(),
        )
        progressionRoutes(component.progressionRepository)
        achievementsRoutes(component.achievementRepository)
        meRoutes(
            component.profileRepository,
            component.supabaseAdminClient,
            component.inventoryRepository,
            component.walletRepository,
            component.progressionRepository,
            component.achievementRepository,
            component.handsFinishedRepository,
            component.userMessageRepository,
            component.roomService,
            component.orphanInstallSweep,
            component.friendRepository,
            component.recentOpponentsRepository,
        )
        grantsRoutes(
            inventory = component.inventoryRepository,
            catalog = component.productCatalogSource,
            clock = component.provideClock(),
        )
        messageRoutes(component.userMessageRepository, component.provideClock())
        avatarRoutes()
        equipmentRoutes(component.equipmentRepository)
        friendsRoutes(component.friendRepository, component.recentOpponentsRepository)
        recentOpponentsRoutes(component.recentOpponentsRepository)
        profilesRoutes(component.profileRepository)
        roomRoutes(component.roomService, component.profileRepository)
        matchmakingRoutes(
            rooms = component.roomService,
            friends = component.friendRepository,
            profiles = component.profileRepository,
            gameSessions = component.gameSessionRegistry,
            equipmentRepository = component.equipmentRepository,
            progressionRepository = component.progressionRepository,
        )
        roomSocketRoutes(
            component.roomService,
            component.gameSessionRegistry,
            component.equipmentRepository,
            component.progressionRepository,
            component.walletRepository,
        )
        adminRoutes(
            config = adminConfig,
            sweep = component.orphanAnonymousSweep,
            rooms = component.roomService,
            wallets = component.walletRepository,
            messages = component.userMessageRepository,
            clock = component.provideClock(),
        )
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
