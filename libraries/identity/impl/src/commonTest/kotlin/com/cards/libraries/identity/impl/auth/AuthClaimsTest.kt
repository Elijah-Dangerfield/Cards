package com.dangerfield.cards.libraries.identity.impl.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class AuthClaimsTest {

    @Test
    fun `reads is_anonymous true from the token payload`() {
        val token = jwt("""{"sub":"u1","is_anonymous":true}""")
        assertEquals(true, isAnonymousFromToken(token))
    }

    @Test
    fun `reads is_anonymous false from the token payload`() {
        val token = jwt("""{"sub":"u1","is_anonymous":false}""")
        assertEquals(false, isAnonymousFromToken(token))
    }

    @Test
    fun `a freshly-linked account carrying is_anonymous false reads non-anonymous even with a stale identities view`() {
        // The device-reproduced AUTH-12 hazard: after a Google link the refreshed
        // JWT stamps is_anonymous=false, but supabase-kt's user.identities may not
        // repopulate reliably. The claim is the authoritative signal the server
        // trusts, so the gateway must prefer it.
        val refreshedToken = jwt("""{"sub":"u1","is_anonymous":false}""")
        assertEquals(
            false,
            deriveIsAnonymous(accessToken = refreshedToken, hasNoIdentities = true),
        )
    }

    @Test
    fun `falls back to the identities heuristic when the claim is absent`() {
        val token = jwt("""{"sub":"u1"}""")
        assertEquals(true, deriveIsAnonymous(accessToken = token, hasNoIdentities = true))
        assertEquals(false, deriveIsAnonymous(accessToken = token, hasNoIdentities = false))
    }

    @Test
    fun `falls back to the identities heuristic when the token is malformed`() {
        assertEquals(true, deriveIsAnonymous(accessToken = "not-a-jwt", hasNoIdentities = true))
        assertEquals(false, deriveIsAnonymous(accessToken = "not-a-jwt", hasNoIdentities = false))
    }

    @Test
    fun `returns null claim for a token whose payload is not JSON`() {
        val token = "${b64("header")}.${b64("garbage")}.sig"
        assertNull(isAnonymousFromToken(token))
    }

    private fun jwt(payloadJson: String): String =
        "${b64("""{"alg":"ES256","typ":"JWT"}""")}.${b64(payloadJson)}.signature"

    private fun b64(value: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(value.encodeToByteArray())
}
