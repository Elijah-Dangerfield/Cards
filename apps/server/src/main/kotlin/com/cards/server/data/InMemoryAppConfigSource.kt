package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppConfigSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hardcoded app-config tree for the dev server. Edit, restart, the change is
 * live on the next client refresh.
 *
 * Production swaps this out for a Postgres-backed source (`PostgresAppConfigSource`)
 * bound to [ServerScope] with the same [@ContributesBinding] annotation; this
 * one drops out automatically.
 *
 * The keys mirror `ConfiguredValue.path` on the client. Anything omitted falls
 * back to the client-side default.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryAppConfigSource : AppConfigSource {

    override suspend fun read(): JsonObject = JsonObject(
        mapOf(
            "upgrade" to JsonObject(
                mapOf(
                    "minSupportedVersionCode" to JsonPrimitive(1),
                    "maintenanceMode" to JsonPrimitive("off"),
                    "maintenanceMessage" to JsonPrimitive("We're updating the servers, back in a moment."),
                ),
            ),
        ),
    )
}
