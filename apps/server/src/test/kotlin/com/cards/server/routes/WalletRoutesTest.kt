package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.MessageSweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `/v1/me/wallet` and `/v1/me/wallet/sync`. The
 * repository is faked so the focus is on the HTTP/JSON layer + JWT
 * gating; the Postgres repo has its own integration tests.
 *
 * Tests mint HS256 JWTs against a controlled secret + matching
 * verifier, mirroring the pattern in [MeRoutesTest].
 */
@OptIn(ExperimentalTime::class)
class WalletRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    @Test
    fun get_returnsBalance_andLazyCreatesWallet() = runTest {
        val repo = FakeWalletRepo()
        callGet(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletResponse>()
            assertEquals(Wallet.STARTER_GRANT, body.balance)
            assertEquals(1, repo.findOrCreateCalls)
        }
    }

    @Test
    fun get_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeWalletRepo()
        callGet(repo, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.findOrCreateCalls)
        }
    }

    @Test
    fun sync_appliesNewEvents_andReturnsAuthoritativeBalance() = runTest {
        val repo = FakeWalletRepo()
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(
                    WalletEventDto(idempotencyKey = "a", delta = 250, reason = "iap"),
                    WalletEventDto(idempotencyKey = "b", delta = 100, reason = "achievement"),
                ),
            ),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(Wallet.STARTER_GRANT + 350, body.balance)
            assertEquals(2, body.results.size)
            assertTrue(body.results.all { it.outcome == WalletEventOutcomeDto.Applied })
        }
    }

    @Test
    fun sync_returnsAlreadyApplied_forReplayedEvents() = runTest {
        val repo = FakeWalletRepo().apply {
            // Pre-seed the duplicate key so the next apply is a replay.
            applied["dupe"] = ApplyOutcome.Applied(
                balance = Wallet.STARTER_GRANT,
                wasAlreadyApplied = false,
            )
        }
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(WalletEventDto(idempotencyKey = "dupe", delta = 50, reason = "test")),
            ),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(WalletEventOutcomeDto.AlreadyApplied, body.results.first().outcome)
            assertEquals(Wallet.STARTER_GRANT, body.balance)
        }
    }

    @Test
    fun sync_returnsInsufficientChips_forDebitBelowZero() = runTest {
        val repo = FakeWalletRepo()
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(
                    WalletEventDto(idempotencyKey = "drain", delta = -20_000, reason = "shop.x"),
                ),
            ),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(WalletEventOutcomeDto.InsufficientChips, body.results.first().outcome)
            assertEquals(Wallet.STARTER_GRANT, body.balance, "balance must not move on rejected debit")
        }
    }

    @Test
    fun sync_continuesProcessing_afterInsufficientChipsRejection() = runTest {
        // A failing debit in the middle must not abort the batch — later
        // events still apply. This mirrors how the client batches mixed
        // grants + debits and we want at-most-one stuck event, not a
        // cascade.
        val repo = FakeWalletRepo()
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(
                    WalletEventDto(idempotencyKey = "g1", delta = 100, reason = "grant"),
                    WalletEventDto(idempotencyKey = "d1", delta = -1_000_000, reason = "bad debit"),
                    WalletEventDto(idempotencyKey = "g2", delta = 200, reason = "grant"),
                ),
            ),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(3, body.results.size)
            assertEquals(WalletEventOutcomeDto.Applied, body.results[0].outcome)
            assertEquals(WalletEventOutcomeDto.InsufficientChips, body.results[1].outcome)
            assertEquals(WalletEventOutcomeDto.Applied, body.results[2].outcome)
            assertEquals(Wallet.STARTER_GRANT + 300, body.balance)
        }
    }

    @Test
    fun sync_acceptsEmptyEvents_andReturnsCurrentBalance() = runTest {
        // Empty sync = "what's my balance?" — useful as a foreground hydrate
        // pulse when the client has no pending events.
        val repo = FakeWalletRepo()
        callSync(
            repo,
            request = WalletSyncRequest(events = emptyList()),
            bearer = validJwt(),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(Wallet.STARTER_GRANT, body.balance)
            assertEquals(0, body.results.size)
        }
    }

    @Test
    fun sync_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeWalletRepo()
        callSync(repo, request = WalletSyncRequest(), bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.applyCalls)
        }
    }

    // ---------- Test scaffolding ----------

    private fun validJwt(): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(userId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    /**
     * Fixed clock used by tests that don't care about the welcome-week
     * timeline. Pairs with the default `welcomeWeekEnabled = false` on
     * [FakeWalletRepo], which pre-seeds every day's idempotency key so
     * the route's welcome-week pass collapses to a no-op.
     */
    private val testClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }

    private suspend fun callGet(
        repo: WalletRepository,
        bearer: String?,
        messages: UserMessageRepository = NoopUserMessages(),
        clock: Clock = testClock,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { walletRoutes(repo, messages, clock) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.get("/v1/me/wallet") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(resp)
        }
    }

    private suspend fun callSync(
        repo: WalletRepository,
        request: WalletSyncRequest,
        bearer: String?,
        messages: UserMessageRepository = NoopUserMessages(),
        clock: Clock = testClock,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { walletRoutes(repo, messages, clock) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val resp = client.post("/v1/me/wallet/sync") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assert(resp)
        }
    }

    /**
     * Deterministic in-memory wallet impl. Tracks one wallet per UserId
     * starting at [Wallet.STARTER_GRANT] and applies events in-process.
     * The `applied` map doubles as an idempotency record + an injection
     * point: pre-populate it to simulate "the server already saw this
     * key" in a replay scenario.
     *
     * `welcomeWeekEnabled` defaults to `false` so tests that don't care
     * about the welcome-week schedule get an inert pass — every day's
     * idempotency key is pre-seeded as already-applied. Welcome-week
     * tests opt in via `welcomeWeekEnabled = true` and configure
     * `walletCreatedAt` + the route's clock together.
     */
    private class FakeWalletRepo(
        seed: Map<UserId, Long> = emptyMap(),
        welcomeWeekEnabled: Boolean = false,
        private val walletCreatedAt: Instant = Instant.fromEpochMilliseconds(0),
    ) : WalletRepository {
        var findOrCreateCalls: Int = 0
            private set
        var applyCalls: Int = 0
            private set

        private val balances: MutableMap<UserId, Long> = seed.toMutableMap()
        val applied: MutableMap<String, ApplyOutcome.Applied> = mutableMapOf()

        init {
            if (!welcomeWeekEnabled) {
                // Pre-seed every welcome-week day's idempotency key as
                // already-applied so tests that don't care about the
                // schedule see an inert pass through `maybeApplyWelcomeWeek`.
                // Schedule shifted 2026-05-23 from days 0..6 to 1..7;
                // pre-seed both spans so future schedule tweaks don't
                // require touching this test infra.
                for (d in 0..Wallet.WELCOME_WEEK_LAST_DAY) {
                    applied["${Wallet.WELCOME_WEEK_KEY_PREFIX}${d}_v1"] =
                        ApplyOutcome.Applied(balance = 0L, wasAlreadyApplied = false)
                }
            }
        }

        override suspend fun findOrCreate(userId: UserId): Wallet {
            findOrCreateCalls++
            val balance = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            return Wallet(
                userId = userId,
                balance = balance,
                createdAt = walletCreatedAt,
                updatedAt = walletCreatedAt,
            )
        }

        override suspend fun find(userId: UserId): Wallet? = balances[userId]?.let {
            Wallet(
                userId = userId,
                balance = it,
                createdAt = walletCreatedAt,
                updatedAt = walletCreatedAt,
            )
        }

        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome {
            applyCalls++
            val current = balances.getOrPut(userId) { Wallet.STARTER_GRANT }
            applied[idempotencyKey]?.let {
                return ApplyOutcome.Applied(
                    balance = current,
                    wasAlreadyApplied = true,
                )
            }
            val next = current + delta
            if (next < 0) return ApplyOutcome.InsufficientChips(balance = current)
            balances[userId] = next
            val outcome = ApplyOutcome.Applied(balance = next, wasAlreadyApplied = false)
            applied[idempotencyKey] = outcome
            return outcome
        }

        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) {
            balances.remove(userId)
        }
    }

    private class NoopUserMessages : UserMessageRepository {
        val created: MutableList<RecordedMessage> = mutableListOf()

        data class RecordedMessage(
            val userId: UserId,
            val idempotencyKey: String,
            val kind: UserMessageKind,
            val title: String,
            val body: String,
        )

        private val keys: MutableSet<Pair<UserId, String>> = mutableSetOf()

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
            val key = userId to idempotencyKey
            val firstTime = keys.add(key)
            if (firstTime) {
                created += RecordedMessage(userId, idempotencyKey, kind, title, body)
            }
            return CreateMessageOutcome(
                message = UserMessage(
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
                ),
                wasAlreadyCreated = !firstTime,
            )
        }

        override suspend fun unreadFor(userId: UserId, now: Instant, limit: Int): List<UserMessage> = emptyList()
        override suspend fun ackMany(userId: UserId, ids: List<UUID>, at: Instant): Int = 0
        override suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult = MessageSweepResult(0, 0)
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    @Test
    fun get_bustProtection_grantsChipsAndQueuesMessage_whenBalanceIsZero() = runTest {
        val repo = FakeWalletRepo(seed = mapOf(userId to 0L))
        val messages = NoopUserMessages()
        callGet(repo, bearer = validJwt(), messages = messages) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletResponse>()
            assertEquals(Wallet.BUST_PROTECTION_GRANT, body.balance)
            val recorded = messages.created.single()
            assertEquals(UserMessageKind.Dialog, recorded.kind)
            assertEquals("Welcome back to the table.", recorded.title)
            assertEquals("${Wallet.BUST_PROTECTION_KEY}_msg", recorded.idempotencyKey)
        }
    }

    @Test
    fun get_bustProtection_isLifetimeOnce() = runTest {
        val repo = FakeWalletRepo(seed = mapOf(userId to 0L)).apply {
            applied[Wallet.BUST_PROTECTION_KEY] = ApplyOutcome.Applied(
                balance = 0L,
                wasAlreadyApplied = false,
            )
        }
        val messages = NoopUserMessages()
        callGet(repo, bearer = validJwt(), messages = messages) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(0L, resp.body<WalletResponse>().balance, "no second grant; user stays at zero")
            assertEquals(0, messages.created.size, "no replayed welcome dialog")
        }
    }

    @Test
    fun sync_bustProtection_firesAfterBatchDrainsBalanceToZero() = runTest {
        val repo = FakeWalletRepo(seed = mapOf(userId to 500L))
        val messages = NoopUserMessages()
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(WalletEventDto(idempotencyKey = "bet", delta = -500, reason = "hand")),
            ),
            bearer = validJwt(),
            messages = messages,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(Wallet.BUST_PROTECTION_GRANT, body.balance, "bust protection kicks in post-batch")
            assertEquals(1, messages.created.size, "welcome dialog queued exactly once")
        }
    }

    @Test
    fun sync_doesNotTriggerBustProtection_whenBalanceStaysPositive() = runTest {
        val repo = FakeWalletRepo()
        val messages = NoopUserMessages()
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(WalletEventDto(idempotencyKey = "bet", delta = -500, reason = "hand")),
            ),
            bearer = validJwt(),
            messages = messages,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(Wallet.STARTER_GRANT - 500, resp.body<WalletSyncResponse>().balance)
            assertEquals(0, messages.created.size, "no welcome dialog when the user didn't bust")
        }
    }

    @Test
    fun get_welcomeWeek_skipsSignupDay() = runTest {
        // Schedule shifted 2026-05-23: signup day (elapsed day 0) is
        // intentionally starter-only. The daily +500 starts the day
        // after signup. Keeps the "here's your starter chips" moment
        // clean and front-loads the bonus arc to feel like an arc
        // (each day after signup, you find more chips waiting).
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(welcomeWeekEnabled = true, walletCreatedAt = createdAt)
        callGet(repo, bearer = validJwt(), clock = fixedClock(createdAt)) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletResponse>()
            assertEquals(
                Wallet.STARTER_GRANT,
                body.balance,
                "signup day = starter grant only; no daily bonus on day 0",
            )
        }
    }

    @Test
    fun get_welcomeWeek_grantsMissedDays_whenUserSkipsOpeningTheApp() = runTest {
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(welcomeWeekEnabled = true, walletCreatedAt = createdAt)
        callGet(
            repo,
            bearer = validJwt(),
            clock = fixedClock(createdAt + 3.days),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletResponse>()
            assertEquals(
                Wallet.STARTER_GRANT + Wallet.WELCOME_WEEK_DAILY_GRANT * 3,
                body.balance,
                "days 1..3 all granted in one pass — no 'you missed yesterday' penalty",
            )
        }
    }

    @Test
    fun get_welcomeWeek_capsAtSevenGrants() = runTest {
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(welcomeWeekEnabled = true, walletCreatedAt = createdAt)
        callGet(
            repo,
            bearer = validJwt(),
            clock = fixedClock(createdAt + 30.days),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletResponse>()
            assertEquals(
                Wallet.STARTER_GRANT + Wallet.WELCOME_WEEK_DAILY_GRANT * Wallet.WELCOME_WEEK_DAYS,
                body.balance,
                "welcome week tops out at WELCOME_WEEK_DAYS grants (days 1..7) " +
                    "regardless of how late the user shows up",
            )
        }
    }

    @Test
    fun get_welcomeWeek_isIdempotent_acrossRepeatedCalls() = runTest {
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(welcomeWeekEnabled = true, walletCreatedAt = createdAt)
        val clock = fixedClock(createdAt + 2.days)
        callGet(repo, bearer = validJwt(), clock = clock) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(
                Wallet.STARTER_GRANT + Wallet.WELCOME_WEEK_DAILY_GRANT * 2,
                resp.body<WalletResponse>().balance,
                "two elapsed days → days 1 and 2 both granted",
            )
        }
        callGet(repo, bearer = validJwt(), clock = clock) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(
                Wallet.STARTER_GRANT + Wallet.WELCOME_WEEK_DAILY_GRANT * 2,
                resp.body<WalletResponse>().balance,
                "second wallet contact at the same time replays the same idempotency keys — no double grant",
            )
        }
    }

    @Test
    fun get_welcomeWeek_doesNotQueueUserMessage() = runTest {
        // Fix the clock past day 0 so the welcome-week grant actually
        // fires. At day 0 (signup) no grant would happen under the new
        // schedule and the "no message" assertion would be vacuous.
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(welcomeWeekEnabled = true, walletCreatedAt = createdAt)
        val messages = NoopUserMessages()
        callGet(repo, bearer = validJwt(), messages = messages, clock = fixedClock(createdAt + 1.days)) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(
                0,
                messages.created.size,
                "welcome week is silent — no dialog, no inbox row, chips just appear",
            )
        }
    }

    @Test
    fun sync_welcomeWeek_appliesBeforeBatch_soDebitsSeeTheCreditedBalance() = runTest {
        // Clock advanced by 1 day so the day-1 grant fires (signup day
        // no longer grants under the post-2026-05-23 schedule).
        val createdAt = Instant.fromEpochMilliseconds(0)
        val repo = FakeWalletRepo(
            seed = mapOf(userId to 100L),
            welcomeWeekEnabled = true,
            walletCreatedAt = createdAt,
        )
        callSync(
            repo,
            request = WalletSyncRequest(
                events = listOf(WalletEventDto(idempotencyKey = "spend", delta = -400, reason = "shop")),
            ),
            bearer = validJwt(),
            clock = fixedClock(createdAt + 1.days),
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<WalletSyncResponse>()
            assertEquals(WalletEventOutcomeDto.Applied, body.results.single().outcome)
            assertEquals(
                200L,
                body.balance,
                "welcome-week +500 lands before the -400 debit, so the debit succeeds and lastBalance is 200",
            )
        }
    }

    private fun fixedClock(at: Instant): Clock = object : Clock {
        override fun now(): Instant = at
    }
}
