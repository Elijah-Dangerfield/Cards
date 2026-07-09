package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.MessageSweepResult
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import com.dangerfield.cards.server.domain.SweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins admin endpoint auth + the surviving admin surfaces. The
 * disconnected-room-member sweep that used to live here was replaced
 * by an in-process reaper in `RoomSocketRoutes`; this file now pins
 * the orphan-anon sweep gating + the rooms-dashboard endpoint.
 *
 * What we pin:
 *  - 401 when no admin token header is present
 *  - 401 when the token is wrong
 *  - 401 when the server has no token configured (would otherwise be
 *    a footgun — a deploy without ADMIN_API_TOKEN should fail closed,
 *    not let any caller trigger sweeps)
 *  - GET /v1/admin/rooms — token-gated, returns one summary per live
 *    room with connected / disconnected counts.
 */
@OptIn(ExperimentalTime::class)
class AdminRoutesTest {

    private val token = "test-admin-token-32-chars-long-x"
    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val json = Json { ignoreUnknownKeys = true }

    // ---------- GET /v1/admin/rooms ----------

    @Test
    fun listRooms_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun listRooms_returnsUnauthorized_whenServerHasNoToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, configuredToken = null) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", "anything")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "unset ADMIN_API_TOKEN must fail closed on the list endpoint too",
            )
        }
    }

    @Test
    fun listRooms_returnsEmpty_whenNoRooms() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(0, body["rooms"]!!.jsonArray.size)
        }
    }

    @Test
    fun listRooms_summarizesEachRoom_withCounts() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val r1 = rooms.createOrFail(host, "Host")
        rooms.markConnected(r1.code, host, connected = true)
        rooms.join(r1.code, alice, "Alice")
        rooms.markConnected(r1.code, alice, connected = false)
        // Second room with a different host.
        val r2 = rooms.createOrFail(alice, "Alice", maxSeats = 4)
        rooms.markConnected(r2.code, alice, connected = true)

        withApp(rooms) { client ->
            val resp = client.get("/v1/admin/rooms") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val list = body["rooms"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, list.size)
            // Find r1 in the list — order is by createdAt then code; both
            // rooms share createdAt in this test so we look up by code.
            val r1Summary = list.first { it["code"]!!.jsonPrimitive.content == r1.code }
            assertEquals(2, r1Summary["memberCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r1Summary["connectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r1Summary["disconnectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("Lobby", r1Summary["status"]!!.jsonPrimitive.content)
            assertEquals(host.value.toString(), r1Summary["hostUserId"]!!.jsonPrimitive.content)

            val r2Summary = list.first { it["code"]!!.jsonPrimitive.content == r2.code }
            assertEquals(1, r2Summary["memberCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, r2Summary["connectedCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, r2Summary["maxSeats"]!!.jsonPrimitive.content.toInt())
        }
    }

    // ---------- POST /v1/admin/grant-chips ----------

    @Test
    fun grantChips_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":500,"reason":"support"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun grantChips_returnsUnauthorized_whenServerHasNoToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, configuredToken = null) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", "anything")
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":500,"reason":"support"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun grantChips_credits_walletAndStampsLedgerWithAdminGrantReason() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":750,"reason":"chargeback refund"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Applied", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(Wallet.STARTER_GRANT + 750, body["balance"]!!.jsonPrimitive.content.toLong())
            assertEquals(1, wallets.applyCalls.size)
            val applied = wallets.applyCalls.single()
            assertEquals(alice, applied.userId)
            assertEquals(750L, applied.delta)
            assertEquals("admin_grant:chargeback refund", applied.reason)
        }
    }

    @Test
    fun grantChips_acceptsNegativeDelta_andReturnsConflictWhenInsufficient() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":-99999999,"reason":"clawback"}""")
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("InsufficientChips", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(Wallet.STARTER_GRANT, body["balance"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun grantChips_passesIdempotencyKeyThrough_andEchoesBack() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val key = "support-ticket-1234"
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":250,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(key, body["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals(key, wallets.applyCalls.single().idempotencyKey)
        }
    }

    @Test
    fun grantChips_generatesIdempotencyKey_whenAbsent() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":250,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val echoed = body["idempotencyKey"]!!.jsonPrimitive.content
            assertTrue(echoed.isNotBlank(), "server should fill an idempotency key when caller omits one")
            assertEquals(echoed, wallets.applyCalls.single().idempotencyKey)
        }
    }

    @Test
    fun grantChips_replayedKey_returnsAlreadyApplied() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val key = "support-ticket-1234"
        withApp(rooms, wallets = wallets) { client ->
            val first = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":300,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBalance = json.parseToJsonElement(first.bodyAsText())
                .jsonObject["balance"]!!.jsonPrimitive.content.toLong()

            val second = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":300,"reason":"refund","idempotencyKey":"$key"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, second.status)
            val body = json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("AlreadyApplied", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(firstBalance, body["balance"]!!.jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onInvalidUserId() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"not-a-uuid","delta":250,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onZeroDelta() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":0,"reason":"refund"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_returnsBadRequest_onBlankReason() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","delta":100,"reason":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size)
        }
    }

    @Test
    fun grantChips_generatedKeysVary_acrossCallsThatOmitTheKey() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        withApp(rooms, wallets = wallets) { client ->
            repeat(2) {
                client.post("/v1/admin/grant-chips") {
                    header("X-Admin-Token", token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"$alice","delta":50,"reason":"refund"}""")
                }
            }
            assertEquals(2, wallets.applyCalls.size)
            assertNotEquals(
                wallets.applyCalls[0].idempotencyKey,
                wallets.applyCalls[1].idempotencyKey,
                "two omitted-key calls must produce distinct server-generated keys",
            )
        }
    }

    // ---------- POST /v1/admin/grant-chips with attached message ----------

    @Test
    fun grantChips_withAttachedMessage_creates_a_dialog_for_the_recipient() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val messages = FakeMessageRepository()
        withApp(rooms, wallets = wallets, messages = messages) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "userId":"$alice","delta":5000,"reason":"holiday gift",
                        "message":{"emoji":"🎁","title":"Merry Christmas","body":"5000 chips on us.","deepLink":"cards://shop"}
                    }""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val msg = body["message"]!!.jsonObject
            assertEquals("🎁", msg["emoji"]!!.jsonPrimitive.content)
            assertEquals("Merry Christmas", msg["title"]!!.jsonPrimitive.content)
            assertEquals("cards://shop", msg["deepLink"]!!.jsonPrimitive.content)
            assertEquals(1, messages.createCalls.size)
            assertEquals(alice, messages.createCalls.single().userId)
        }
    }

    @Test
    fun grantChips_replay_does_not_re_attach_dialog() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val messages = FakeMessageRepository()
        val key = "ticket-9999"
        withApp(rooms, wallets = wallets, messages = messages) { client ->
            val firstBody =
                """{
                    "userId":"$alice","delta":1000,"reason":"refund","idempotencyKey":"$key",
                    "message":{"title":"Refunded","body":"Sorry about that."}
                }"""
            val first = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json); setBody(firstBody)
            }
            assertEquals(HttpStatusCode.OK, first.status)
            val second = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json); setBody(firstBody)
            }
            val secondBody = json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("AlreadyApplied", secondBody["outcome"]!!.jsonPrimitive.content)
            // explicitNulls=false on the server serializer omits null fields entirely,
            // so "no attached message" lands as the field being absent.
            assertNull(secondBody["message"], "replay must not re-attach the dialog")
            assertEquals(1, messages.createCalls.size, "only one dialog ever created")
        }
    }

    @Test
    fun grantChips_insufficientChips_does_not_attach_dialog() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val messages = FakeMessageRepository()
        withApp(rooms, wallets = wallets, messages = messages) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "userId":"$alice","delta":-99999999,"reason":"clawback",
                        "message":{"title":"You owe us","body":"Big debit incoming."}
                    }""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals(0, messages.createCalls.size, "no dialog when the chips didn't move")
        }
    }

    @Test
    fun grantChips_withInvalidMessage_returns400_andDoesNotApplyChips() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val wallets = FakeWalletRepository()
        val messages = FakeMessageRepository()
        withApp(rooms, wallets = wallets, messages = messages) { client ->
            val resp = client.post("/v1/admin/grant-chips") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","delta":500,"reason":"refund","message":{"title":"  ","body":"x"}}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, wallets.applyCalls.size, "must validate message before touching wallet")
            assertEquals(0, messages.createCalls.size)
        }
    }

    // ---------- POST /v1/admin/messages ----------

    @Test
    fun sendMessage_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","message":{"title":"T","body":"B"}}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun sendMessage_createsRow_andReturnsScheduled() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "userId":"$alice",
                        "message":{"emoji":"⚠️","title":"Heads up","body":"Maintenance Sunday 9pm.","deepLink":null}
                    }""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Scheduled", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(1, messages.createCalls.size)
            assertEquals("⚠️", messages.createCalls.single().emoji)
            assertEquals("Heads up", messages.createCalls.single().title)
        }
    }

    @Test
    fun sendMessage_replayedKey_returnsAlreadyScheduled() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        val key = "k1"
        withApp(rooms, messages = messages) { client ->
            val payload =
                """{"userId":"$alice","idempotencyKey":"$key","message":{"title":"T","body":"B"}}"""
            client.post("/v1/admin/messages") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json); setBody(payload)
            }
            val replay = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json); setBody(payload)
            }
            val body = json.parseToJsonElement(replay.bodyAsText()).jsonObject
            assertEquals("AlreadyScheduled", body["outcome"]!!.jsonPrimitive.content)
            assertEquals(1, messages.createCalls.size, "second call must not create a second row")
        }
    }

    @Test
    fun sendMessage_returns400_onInvalidUserId() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"nope","message":{"title":"T","body":"B"}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, messages.createCalls.size)
        }
    }

    @Test
    fun sendMessage_returns400_onBlankTitleAndBody() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val blankTitle = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","message":{"title":"  ","body":"B"}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, blankTitle.status)
            val blankBody = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","message":{"title":"T","body":""}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, blankBody.status)
            assertEquals(0, messages.createCalls.size)
        }
    }

    @Test
    fun sendMessage_acceptsInboxKind() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","kind":"inbox","message":{"title":"Maintenance","body":"Sunday 9pm"}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(UserMessageKind.Inbox, messages.createCalls.single().kind)
        }
    }

    @Test
    fun sendMessage_defaultsToDialogKind_whenOmitted() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","message":{"title":"T","body":"B"}}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(UserMessageKind.Dialog, messages.createCalls.single().kind)
        }
    }

    @Test
    fun sendMessage_returns400_onUnknownKind() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","kind":"banner","message":{"title":"T","body":"B"}}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, messages.createCalls.size)
        }
    }

    @Test
    fun sendMessage_passesExpiresAt_throughToRepo() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        val futureMs = 2_000_000_000_000L
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"userId":"$alice","message":{"title":"T","body":"B","expiresAtEpochMs":$futureMs}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(
                Instant.fromEpochMilliseconds(futureMs),
                messages.createCalls.single().expiresAt,
            )
        }
    }

    // ---------- POST /v1/admin/sweep-expired-messages ----------

    @Test
    fun sweepExpiredMessages_returnsUnauthorized_withoutToken() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.post("/v1/admin/sweep-expired-messages")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun sweepExpiredMessages_returnsCounts_onHappyPath() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = object : FakeMessageRepository() {
            override suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult =
                MessageSweepResult(ackedPurged = 4, expiredUnackedPurged = 1)
        }
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/sweep-expired-messages") {
                header("X-Admin-Token", token)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(4, body["ackedPurged"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, body["expiredUnackedPurged"]!!.jsonPrimitive.content.toInt())
            assertEquals(5, body["total"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun sendMessage_returns400_onOversizedFields() = runTest {
        val clock = AdvanceableClock()
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val messages = FakeMessageRepository()
        val longTitle = "x".repeat(200)
        withApp(rooms, messages = messages) { client ->
            val resp = client.post("/v1/admin/messages") {
                header("X-Admin-Token", token); contentType(ContentType.Application.Json)
                setBody("""{"userId":"$alice","message":{"title":"$longTitle","body":"B"}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(0, messages.createCalls.size)
        }
    }

    // ---------- scaffolding ----------

    private suspend fun withApp(
        rooms: InMemoryRoomService,
        configuredToken: String? = token,
        wallets: WalletRepository = FakeWalletRepository(),
        messages: UserMessageRepository = FakeMessageRepository(),
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing {
                    adminRoutes(
                        config = AdminConfig(
                            apiToken = configuredToken,
                            orphanAnonTtlDays = 30,
                            staleRoomTtlHours = 6,
                        ),
                        sweep = NoopSweep,
                        rooms = rooms,
                        wallets = wallets,
                        messages = messages,
                        clock = object : Clock {
                            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
                        },
                    )
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(client)
        }
    }

    private object NoopSweep : OrphanAnonymousSweep {
        override suspend fun run(maxInactiveAge: Duration): SweepResult =
            SweepResult(candidatesFound = 0, deleted = 0, failedToDelete = 0, notConfigured = false)
    }

    private data class AppliedCall(
        val userId: UserId,
        val idempotencyKey: String,
        val delta: Long,
        val reason: String,
    )

    /**
     * In-memory wallet impl matching the production semantics: lazy
     * create with [Wallet.STARTER_GRANT], non-negative balance invariant,
     * replay-detection on the per-user idempotency key. Exposes
     * [applyCalls] so tests can inspect what was forwarded.
     */
    private class FakeWalletRepository : WalletRepository {
        private val balances = mutableMapOf<UserId, Long>()
        private val keys = mutableMapOf<UserId, MutableSet<String>>()
        val applyCalls: MutableList<AppliedCall> = mutableListOf()

        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult {
            val isNew = userId !in balances
            val balance = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            return FindOrCreateResult(
                wallet = Wallet(
                    userId = userId,
                    balance = balance,
                    createdAt = Instant.fromEpochMilliseconds(0),
                    updatedAt = Instant.fromEpochMilliseconds(0),
                ),
                created = isNew,
            )
        }

        override suspend fun find(userId: UserId): Wallet? = balances[userId]?.let {
            Wallet(
                userId = userId,
                balance = it,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        }

        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome {
            applyCalls += AppliedCall(userId, idempotencyKey, delta, reason)
            val current = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            val seen = keys.getOrPut(userId) { mutableSetOf() }
            if (idempotencyKey in seen) {
                return ApplyOutcome.Applied(balance = current, wasAlreadyApplied = true)
            }
            val next = current + delta
            if (next < 0) return ApplyOutcome.InsufficientChips(balance = current)
            balances[userId] = next
            seen += idempotencyKey
            return ApplyOutcome.Applied(balance = next, wasAlreadyApplied = false)
        }

        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
        override suspend fun hasIapSpend(userId: UserId): Boolean = false
        override suspend fun deleteAllForUser(userId: UserId) {
            balances.remove(userId)
            keys.remove(userId)
        }
    }

    private data class CreateMessageCall(
        val userId: UserId,
        val idempotencyKey: String,
        val kind: UserMessageKind,
        val emoji: String?,
        val title: String,
        val body: String,
        val deepLink: String?,
        val expiresAt: Instant?,
    )

    /**
     * In-memory user-message repo with the same idempotency semantics as
     * the Postgres impl: a duplicate (userId, idempotencyKey) returns
     * the original row with `wasAlreadyCreated = true`.
     */
    private open class FakeMessageRepository : UserMessageRepository {
        /** Inserts only — replays don't append here, matching the Postgres
         *  impl's "one row per (userId, key)" semantics. Use [invocationCount]
         *  if you specifically want raw call counts. */
        val createCalls: MutableList<CreateMessageCall> = mutableListOf()
        var invocationCount: Int = 0
            private set

        private val byKey = mutableMapOf<Pair<UserId, String>, UserMessage>()

        override suspend fun create(
            id: UUID,
            userId: UserId,
            idempotencyKey: String,
            kind: UserMessageKind,
            emoji: String?,
            title: String,
            body: String,
            deepLink: String?,
            expiresAt: Instant?,
        ): CreateMessageOutcome {
            invocationCount++
            val key = userId to idempotencyKey
            byKey[key]?.let { return CreateMessageOutcome(message = it, wasAlreadyCreated = true) }
            createCalls += CreateMessageCall(userId, idempotencyKey, kind, emoji, title, body, deepLink, expiresAt)
            val message = UserMessage(
                id = id,
                userId = userId,
                idempotencyKey = idempotencyKey,
                kind = kind,
                emoji = emoji,
                title = title,
                body = body,
                deepLink = deepLink,
                createdAt = Instant.fromEpochMilliseconds(0),
                expiresAt = expiresAt,
                ackedAt = null,
            )
            byKey[key] = message
            return CreateMessageOutcome(message = message, wasAlreadyCreated = false)
        }

        override suspend fun unreadFor(
            userId: UserId,
            now: Instant,
            limit: Int,
        ): List<UserMessage> =
            byKey.values.filter { it.userId == userId && it.ackedAt == null }.take(limit)

        override suspend fun ackMany(
            userId: UserId,
            ids: List<UUID>,
            at: Instant,
        ): Int = 0

        override suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult =
            MessageSweepResult(0, 0)

        override suspend fun deleteAllForUser(userId: UserId) {
            byKey.keys.removeAll { it.first == userId }
        }
    }

    /** Minute-grained mutable clock — tests step by integer minutes. */
    private class AdvanceableClock(startMs: Long = 1_700_000_000_000) : Clock {
        private var current: Instant = Instant.fromEpochMilliseconds(startMs)
        override fun now(): Instant = current
        fun advance(minutes: Int) {
            current = current + minutes.minutes
        }
    }
}
