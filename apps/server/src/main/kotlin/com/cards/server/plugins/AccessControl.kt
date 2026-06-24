package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.domain.ModerationRepository
import kotlinx.serialization.Serializable

/**
 * Wire body returned with a `403` when a banned user hits any authenticated
 * route. The contract is **locked machine-readable data only** — no
 * user-facing copy. The client keys its localized blocking screen off
 * [reason] (see docs/todo.md, AGENTS.md). [until] is an ISO-8601 timestamp
 * when the block lifts (null = indefinite); [appealUrl] points at a support
 * page (null when the server has none configured).
 *
 * camelCase fields match the rest of the server's JSON contract (e.g.
 * `MeResponse.isAnonymous`); the client deserializes the whole envelope.
 */
@Serializable
data class AccessDeniedResponse(
    val reason: String,
    val until: String? = null,
    val appealUrl: String? = null,
)

/**
 * Bundles the moderation read + the appeal URL the auth layer needs to gate
 * banned callers. Passed into [installAuthentication]; when present, a banned
 * user's token validates to "no principal" and the auth challenge renders a
 * `403` [AccessDeniedResponse] instead of the default `401`.
 *
 * Folding the gate into the auth flow (rather than a separate post-auth
 * plugin) is deliberate: the JWT provider's validate→challenge path is the
 * one place in Ktor that reliably short-circuits the routing pipeline, so a
 * banned caller never reaches a route handler.
 */
class BanGate(
    val moderation: ModerationRepository,
    val appealUrl: String?,
)
