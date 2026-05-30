package com.dangerfield.cards.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the header precedence for [clientIp]. The function gates every
 * per-IP rate-limit bucket ([installRateLimits]) and the wrong
 * precedence — picking Fly's edge address over the original client —
 * degenerates the per-IP buckets into a single global bucket, which
 * would let one bad actor exhaust every limiter for all users sharing
 * the Fly edge IP.
 *
 * The precedence is documented but historically untested:
 *   1. `Fly-Client-IP` (the edge sets this with the real origin IP).
 *   2. `X-Forwarded-For` first hop (when the request transits a CDN
 *      that adds XFF rather than Fly-Client-IP).
 *   3. Socket address (local dev / tests / bypassed proxy).
 *
 * Each tier also has a blankness guard — an empty header value at one
 * tier should fall through to the next rather than satisfy the lookup
 * and key everyone into a "" bucket.
 */
class ClientIpTest {

    @Test
    fun flyClientIp_wins_overEverything() = runTest {
        whoami(
            headers = mapOf(
                "Fly-Client-IP" to "1.2.3.4",
                "X-Forwarded-For" to "5.6.7.8, 9.10.11.12",
            ),
        ) { ip ->
            assertEquals("1.2.3.4", ip)
        }
    }

    @Test
    fun xForwardedFor_wins_whenFlyClientIpAbsent() = runTest {
        whoami(
            headers = mapOf("X-Forwarded-For" to "5.6.7.8"),
        ) { ip ->
            assertEquals("5.6.7.8", ip)
        }
    }

    @Test
    fun xForwardedFor_picksFirstHop_whenMultipleHopsListed() = runTest {
        // RFC 7239 §5.2 leaves "first or last" up to convention. Ours is
        // "first" (the original client) — anything else lets a hop add
        // itself to the front and squash buckets. Pin the first-hop
        // choice so a future refactor that flips to `.last()` (because
        // "the last hop is the proxy that touched us" sounds reasonable)
        // breaks loudly.
        whoami(
            headers = mapOf("X-Forwarded-For" to "5.6.7.8, 9.10.11.12, 13.14.15.16"),
        ) { ip ->
            assertEquals("5.6.7.8", ip)
        }
    }

    @Test
    fun xForwardedFor_trimsWhitespace_aroundFirstHop() {
        runTest {
            whoami(
                headers = mapOf("X-Forwarded-For" to "  5.6.7.8  , 9.10.11.12"),
            ) { ip ->
                assertEquals("5.6.7.8", ip)
            }
        }
    }

    @Test
    fun flyClientIp_blank_fallsThroughToXForwardedFor() {
        // Header present but empty (a misbehaving proxy or test
        // tampering) must NOT be accepted — otherwise every blank
        // header floods into a single "" bucket and the limiter falls
        // apart for everyone behind that proxy.
        runTest {
            whoami(
                headers = mapOf(
                    "Fly-Client-IP" to "",
                    "X-Forwarded-For" to "5.6.7.8",
                ),
            ) { ip ->
                assertEquals("5.6.7.8", ip)
            }
        }
    }

    @Test
    fun xForwardedFor_blankFirstHop_fallsThroughToSocketAddress() {
        runTest {
            whoami(
                headers = mapOf("X-Forwarded-For" to "  , 9.10.11.12"),
            ) { ip ->
                // The first hop trims to empty → fall through to the
                // socket address (assertion only checks "not the
                // second hop" because the local socket varies).
                assertEquals(false, ip == "9.10.11.12", "blank first hop must not silently shift to the next entry")
            }
        }
    }

    @Test
    fun noHeaders_returnsNonEmptySocketAddress() {
        // Validates the "we always have something to key on" contract —
        // even without proxy headers, requestKey { } gets a non-empty
        // string. The exact host is environment-dependent so we don't
        // pin the value, just non-emptiness.
        runTest {
            whoami(headers = emptyMap()) { ip ->
                assertEquals(true, ip.isNotBlank(), "socket-address fallback must produce a non-blank key")
            }
        }
    }

    private suspend fun whoami(
        headers: Map<String, String>,
        assertions: (String) -> Unit,
    ) {
        testApplication {
            application {
                routing {
                    get("/whoami") {
                        call.respondText(call.clientIp())
                    }
                }
            }
            val resp = createClient { }.get("/whoami") {
                headers.forEach { (name, value) -> header(name, value) }
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertions(resp.bodyAsText())
        }
    }
}
