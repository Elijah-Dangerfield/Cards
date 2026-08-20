package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.ExpectedControlFlow
import com.dangerfield.cards.libraries.networking.BannedState
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Refusing to send a request the server has already told us it will reject.
 *
 * Carries [ExpectedControlFlow] so the telemetry sinks drop it — a blocked
 * request is the breaker working, not a failure. Callers see it as an ordinary
 * `Catching` failure and take their existing error path.
 */
class BannedShortCircuit(val path: String) : Exception(
    "Blocked locally: this account is denied access and $path is not part of the recovery path",
), ExpectedControlFlow

/**
 * Cuts every non-allowlisted request while [BannedState.isBanned].
 *
 * Before this, a banned user's client kept working normally: each call went out,
 * came back `403 {"reason":"banned"}`, and logged an error (Sentry CARDS-BG).
 * The user saw the blocking screen the whole time, so none of that traffic could
 * have changed anything.
 *
 * The allowlist is the recovery path, and nothing else:
 *  - `/v1/me` exactly — the account-status probe. Its `200` is what clears the
 *    ban, so blocking it would make the block permanent. The sync writes nested
 *    underneath it are not allowlisted, which is why the match is exact rather
 *    than a prefix.
 *  - `/v1/app-config` — remote config, including the kill switches. A blocked
 *    client that can't read config is a client we can no longer steer.
 */
internal fun bannedCircuitBreaker(bannedState: BannedState): ClientPlugin<Unit> =
    createClientPlugin("BannedCircuitBreaker") {
        onRequest { request, _ ->
            if (!bannedState.isBanned) return@onRequest
            // The builder has no assembled path yet, only segments — an outbound
            // "/v1/me" is ["", "v1", "me"], so rejoining restores the leading /.
            val path = request.url.encodedPathSegments.joinToString(separator = "/")
            if (!isRecoveryPath(path)) throw BannedShortCircuit(path)
        }
    }

private fun isRecoveryPath(path: String): Boolean =
    path == ACCOUNT_STATUS_PATH || path.startsWith(APP_CONFIG_PATH)

/** The status probe, and the only thing whose `200` lifts a local ban. */
internal const val ACCOUNT_STATUS_PATH = "/v1/me"
private const val APP_CONFIG_PATH = "/v1/app-config"
