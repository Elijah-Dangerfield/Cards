package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.Catching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Authoritative anonymity decision for a Supabase session.
 *
 * The access token is the signal the *server* trusts: every Supabase JWT
 * (anonymous and claimed) carries an `is_anonymous` claim, and a link/claim
 * refresh re-stamps it to false. supabase-kt's `user.identities` list, by
 * contrast, doesn't always repopulate after a link on the client (device- and
 * version-dependent), which is what left a just-claimed account still reading
 * "anonymous" in-app (AUTH-12 / CARDS-76).
 *
 * So: read the claim first, fall back to the identities heuristic only when the
 * token can't be decoded or omits the claim.
 */
internal fun deriveIsAnonymous(accessToken: String, hasNoIdentities: Boolean): Boolean =
    isAnonymousFromToken(accessToken) ?: hasNoIdentities

private val claimsJson = Json { ignoreUnknownKeys = true }

/**
 * The `is_anonymous` claim from a JWT access token's payload, or null when the
 * token is malformed or omits the claim. Signature is never verified here — the
 * server verifies it; the client only reads a claim from a token it already
 * holds.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun isAnonymousFromToken(accessToken: String): Boolean? = Catching {
    val payload = accessToken.split('.').getOrNull(1) ?: return@Catching null
    val decoded = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .decode(payload)
        .decodeToString()
    claimsJson.parseToJsonElement(decoded)
        .jsonObject["is_anonymous"]
        ?.jsonPrimitive
        ?.booleanOrNull
}.getOrNull()
