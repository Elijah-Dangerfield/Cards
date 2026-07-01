package com.cards.integration.helpers

import com.dangerfield.cards.server.data.DefaultTableSessionRecoverySweep
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.RoomTeardownCoordinator
import com.dangerfield.cards.server.game.SessionSnapshot
import com.dangerfield.cards.server.game.SessionSnapshotStore
import com.dangerfield.cards.server.plugins.installAuthentication
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import com.dangerfield.cards.server.plugins.installWebSockets
import com.dangerfield.cards.server.routes.matchmakingRoutes
import com.dangerfield.cards.server.routes.roomRoutes
import com.dangerfield.cards.server.routes.roomSocketRoutes
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.routes.DEFAULT_REAPER_GRACE
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A real Ktor server — the production room HTTP + WebSocket routes, in-memory
 * storage, real gameplay engine, test JWT auth — bound to an ephemeral port.
 *
 * Bound to a real port on purpose: the production client builds its own Ktor
 * engine and talks over real TCP, which Ktor's in-memory `testApplication`
 * engine can't serve. No Postgres/Docker — storage is in-memory and auth is
 * stubbed, so this runs as a plain host-JVM unit test.
 *
 * [reaperGrace] is the disconnect-grace window the socket routes schedule before
 * freeing a dropped seat. Production is 5 minutes; a test that exercises the
 * grace boundary (reaper category) passes a sub-second window so the timer
 * actually fires inside the test. The fakes ([wallets], [tableSessions]) are
 * per-server instances so a test can read wallet balances to assert escrow.
 *
 * Durable state ([rooms], [snapshots]) is held by *this* instance, not the netty
 * engine, so [restart] can drop the engine + its in-memory registry (simulating
 * a process bounce) while the room membership + the persisted session snapshot
 * survive — exactly what production keeps in Postgres. A client whose socket
 * dropped at restart reconnects to the new engine and the registry rehydrates
 * the live hand from [snapshots].
 */
class InProcessServer(
    private val reaperGrace: Duration = DEFAULT_REAPER_GRACE,
    // Heads-up match-over rebuy grace (MP-14). Short by default so a
    // bust-to-match-over test resolves in-test rather than waiting the real 60s,
    // but long enough that a rebuy test can fire its rebuy before the window
    // closes. Safe because no passive-play test ever busts a seat to trigger it.
    private val matchOverGraceMillis: Long = 1_500L,
    // The universal between-hands beat. Tiny by default so a multi-hand test isn't
    // gated on real seconds per boundary; a reconnect/resync test that needs to
    // observe the *completed* hand before it auto-advances passes a larger window
    // so the table parks there across the reconnect (as a real client would see
    // during the beat).
    private val nextHandBeatMs: Long = 50L,
) : AutoCloseable {

    /** Per-server wallet + escrow fakes, exposed so escrow tests can read balances. */
    val wallets = FakeWallets()
    private val tableSessions = FakeTableSessions(wallets)

    // Stands in for the production `room_sessions` Postgres table: survives a
    // [restart] so the registry built by the fresh engine can rehydrate a live
    // hand the dropped client reconnects to.
    private val snapshots = InMemorySessionSnapshotStore()

    // Scripted decks per room (MP-18). Held on this instance (not the rebuilt
    // registry) so a script survives [restart], and consulted by every engine's
    // registry via the deckSource below. A test calls [scriptDeck] before the
    // deal to force a deterministic outcome (a bust, a chop, a side pot);
    // unscripted rooms fall back to a freshly shuffled deck.
    private val deckScripts = ConcurrentHashMap<String, (Int) -> Deck>()

    // The live engine's registry. Rebuilt per engine ([buildEngine]) so a restart
    // drops the old engine's in-memory sessions; the durable [snapshots] is what
    // the fresh registry rehydrates from. Bots/turn-timers run on the registry's
    // own scope inside the engine.
    @Volatile
    private var registry: GameSessionRegistry = buildRegistry()

    // Wire the REAL teardown coordinator (production binds this via DI) so a room
    // GC settles every seated human's escrow + ends the session — without it the
    // harness used NoOp, so the last member out of a room was never cashed out
    // and chip conservation across teardown couldn't be asserted.
    //
    // The room service is held by this instance (not rebuilt on restart) because
    // production room membership lives in Postgres and survives a bounce; the
    // socket connect path looks the room up by code, so it must still be there
    // post-restart. Its teardown coordinator forwards to [registry], which always
    // points at the current engine's registry.
    private val rooms = InMemoryRoomService(
        clock = Clock.System,
        random = Random(0),
        roomClosedListener = RoomTeardownCoordinator(CurrentRegistry(), tableSessions),
    )

    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        buildEngine(registry, port = 0).start(wait = false)

    /** The bound port — fixed across [restart] so a client's `baseUrl` stays valid. */
    private val boundPort: Int = runBlocking {
        engine.engine.resolvedConnectors().first().port
    }

    /** The base URL a client's `NetworkConfig` should point at. */
    val baseUrl: String get() = "http://127.0.0.1:$boundPort"

    /** Forwards to the live engine's [registry]; lets the shared room teardown reach across a restart. */
    private inner class CurrentRegistry : GameSessionRegistry by registry {
        // Delegation captures the field reference at construction, so override the
        // members the teardown path touches to read the current value each call.
        override fun peek(code: String) = registry.peek(code)
        override suspend fun end(code: String) = registry.end(code)
    }

    // Shrink the between-hands beat + bot think-time so a multi-hand test over the
    // real wire isn't gated on real seconds per hand (production holds ~6s between
    // hands and a humanlike per-turn pause that can tank to several seconds).
    private fun buildRegistry(): GameSessionRegistry = DefaultGameSessionRegistry(
        snapshots,
        Clock.System,
        nextHandBeatMs = nextHandBeatMs,
        botThinkDelayMsOverride = 0,
        matchOverGraceMillis = matchOverGraceMillis,
        deckSource = { code, handNumber -> deckScripts[code]?.invoke(handNumber) },
    )

    /**
     * Force the cards a room is dealt (MP-18). Register before the deal — every
     * hand in [code] draws [deck]. Build it with [stackedDeck] so a test can spell
     * out "seat 0 gets AA, seat 1 gets KK, the board misses" and bust a player on
     * purpose. Overload below scripts per hand number for multi-hand scenarios.
     */
    fun scriptDeck(code: String, deck: Deck) {
        deckScripts[code] = { deck }
    }

    fun scriptDeck(code: String, deckByHand: (handNumber: Int) -> Deck) {
        deckScripts[code] = deckByHand
    }

    private fun buildEngine(
        registry: GameSessionRegistry,
        port: Int,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        return embeddedServer(Netty, port = port) {
            installSerialization()
            installStatusPages()
            installRateLimits()
            installWebSockets()
            installAuthentication(IntegrationAuth.verification)
            routing {
                roomRoutes(rooms, FakeProfiles, wallets)
                matchmakingRoutes(
                    rooms = rooms,
                    friends = FakeFriends,
                    profiles = FakeProfiles,
                    gameSessions = registry,
                    tableSessions = tableSessions,
                    equipmentRepository = FakeEquipment,
                    progressionRepository = FakeProgression,
                    wallets = wallets,
                )
                roomSocketRoutes(
                    rooms = rooms,
                    gameSessions = registry,
                    equipmentRepository = FakeEquipment,
                    progressionRepository = FakeProgression,
                    wallets = wallets,
                    tableSessions = tableSessions,
                    reaperGrace = reaperGrace,
                )
            }
        }
    }

    /**
     * Bounce the process: stop the current netty engine (dropping its in-memory
     * game registry — the live sessions vanish, just like a real crash) and start
     * a fresh engine on the **same** port reusing the durable [rooms] + [snapshots]
     * state. A client whose socket was open when this runs sees the connection
     * drop, then its [ReconnectingRoomSocket] dials the new engine, which
     * rehydrates the hand from the snapshot.
     */
    fun restart() {
        // Stop + start on a dedicated thread: Ktor's stop() drives a blocking
        // application teardown that cancels the engine's coroutines, and run from
        // the test's own runBlocking scope that JobCancellationException bubbles
        // up and fails the test. A separate thread isolates the bounce from the
        // caller's coroutine context.
        val bounce = Thread {
            engine.stop(0, 0)
            registry = buildRegistry()
            engine = buildEngine(registry, port = boundPort).start(wait = false)
        }
        bounce.start()
        bounce.join()
    }

    /** Current wallet balance for [userId] (a UUID string), or null if never touched. */
    fun walletBalance(userId: String): Long? = runBlocking {
        wallets.find(UserId(UUID.fromString(userId)))?.balance
    }

    /**
     * Read-only probe: does a room with [code] still exist server-side? Use to
     * assert a room was GC'd (reaper / last-leave) without a mutating join, which
     * would itself re-create membership and keep the room alive.
     */
    fun roomExists(code: String): Boolean = runBlocking { rooms.find(code) != null }

    /** Did the room reach the terminal Finished state (e.g. a heads-up match-over)? */
    fun roomIsFinished(code: String): Boolean = runBlocking {
        rooms.find(code)?.status == com.dangerfield.cards.server.domain.RoomStatus.Finished
    }

    /**
     * Does [userId] still hold an open table session (escrow not yet cashed out)?
     * Lets a test wait for an async cash-out to actually fire before asserting the
     * wallet — needed when the correct refund (0, for a busted player) leaves the
     * balance unchanged, so the balance alone can't tell "not yet" from "settled".
     */
    fun hasActiveTableSession(userId: String): Boolean =
        tableSessions.isActive(UserId(UUID.fromString(userId)))

    /**
     * Run the production boot recovery sweep ([DefaultTableSessionRecoverySweep])
     * over this server's durable state — the open sessions in [tableSessions] and
     * the session snapshots in [snapshots] that survive a [restart]. Models the
     * once-at-boot sweep `Application.module` fires after a crash: every still-open
     * session is cashed out from its room's last durable snapshot. Returns the
     * count settled. The crash-recovery test bounces the process via [restart] (so
     * the in-memory registry + its `lastKnownStacks` are gone, exactly like a real
     * crash), then drives this sweep to prove a busted-and-dropped player is cashed
     * out 0 from the persisted snapshot rather than refunded their escrow (MP-13).
     */
    fun sweepAbandonedSessions(): Int = runBlocking {
        DefaultTableSessionRecoverySweep(
            tableSessions = HarnessTableSessionRepository(tableSessions),
            tableSessionService = tableSessions,
            snapshots = snapshots,
        ).sweepAbandonedSessions()
    }

    override fun close() {
        engine.stop(0, 0)
    }
}

/**
 * In-memory [SessionSnapshotStore] that survives an [InProcessServer.restart]
 * because the same instance is handed to every engine the server builds. Stands
 * in for [com.dangerfield.cards.server.game.PostgresSessionSnapshotStore] without
 * a Postgres in the loop. Concurrent because the engine persists frames off the
 * per-session mutex while a test thread restarts.
 */
private class InMemorySessionSnapshotStore : SessionSnapshotStore {
    private val byCode = ConcurrentHashMap<String, SessionSnapshot>()

    override suspend fun upsert(snapshot: SessionSnapshot) {
        byCode[snapshot.code] = snapshot
    }

    override suspend fun readByCode(code: String): SessionSnapshot? = byCode[code]

    override suspend fun deleteByCode(code: String) {
        byCode.remove(code)
    }
}

/** Server-side profile source — a deterministic display name per user. */
private object FakeProfiles : ProfileRepository {
    override suspend fun findById(userId: UserId): Profile? = null
    override suspend fun findOrCreate(userId: UserId): Profile = Profile(
        userId = userId,
        displayName = "P-${userId.value.toString().take(4)}",
        avatarEmoji = "🃏",
        avatarBackgroundColor = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    override suspend fun update(
        userId: UserId,
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("unused in the integration harness")

    override suspend fun delete(userId: UserId) = Unit
    override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = null
    override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> =
        emptyList()
}

/** Friend graph with no blocks — matchmaking seats anyone with anyone. */
private object FakeFriends : com.dangerfield.cards.server.domain.FriendRepository {
    override suspend fun sendRequest(from: UserId, to: UserId) =
        com.dangerfield.cards.server.domain.SendRequestResult.Sent
    override suspend fun accept(me: UserId, other: UserId) =
        com.dangerfield.cards.server.domain.RespondResult.Ok
    override suspend fun decline(me: UserId, other: UserId) =
        com.dangerfield.cards.server.domain.RespondResult.Ok
    override suspend fun block(me: UserId, other: UserId) = Unit
    override suspend fun listFriends(userId: UserId): List<UserId> = emptyList()
    override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = emptySet()
    override suspend fun listIncomingRequests(userId: UserId): List<UserId> = emptyList()
    override suspend fun deleteAllForUser(userId: UserId) = Unit
}

/** No-op equipment source — the integration harness doesn't seat badges. */
private object FakeEquipment : com.dangerfield.cards.server.domain.EquipmentRepository {
    override suspend fun listEquipped(userId: UserId): List<com.dangerfield.cards.server.domain.EquippedItem> =
        emptyList()
    override suspend fun equip(
        userId: UserId,
        productId: String,
        newUpdatedAt: Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem = error("unused in the integration harness")
    override suspend fun unequip(
        userId: UserId,
        productId: String,
        opUpdatedAt: Instant,
    ): com.dangerfield.cards.server.domain.EquippedItem? = null
}

/**
 * In-memory wallet source for the harness — backs the MP buy-in / rebuy escrow
 * the room socket runs. Every user starts at [com.dangerfield.cards.server.domain.Wallet.STARTER_GRANT];
 * [apply] is idempotent per (user, key) and rejects a debit that would drive the
 * balance negative, mirroring the real repository's contract closely enough for
 * full-hand play. Synchronized because sockets apply concurrently.
 */
class FakeWallets : com.dangerfield.cards.server.domain.WalletRepository {
    private val lock = Any()
    private val balances = mutableMapOf<UserId, Long>()
    private val appliedKeys = mutableSetOf<Pair<UserId, String>>()

    // Caller holds [lock].
    private fun balanceOf(userId: UserId): Long =
        balances.getOrPut(userId) { com.dangerfield.cards.server.domain.Wallet.STARTER_GRANT }

    private fun wallet(userId: UserId, balance: Long) = com.dangerfield.cards.server.domain.Wallet(
        userId = userId,
        balance = balance,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    override suspend fun findOrCreateResult(
        userId: UserId,
    ): com.dangerfield.cards.server.domain.FindOrCreateResult = synchronized(lock) {
        val created = userId !in balances
        com.dangerfield.cards.server.domain.FindOrCreateResult(wallet(userId, balanceOf(userId)), created)
    }

    override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.Wallet? =
        synchronized(lock) { balances[userId]?.let { wallet(userId, it) } }

    override suspend fun apply(
        userId: UserId,
        idempotencyKey: String,
        delta: Long,
        reason: String,
    ): com.dangerfield.cards.server.domain.ApplyOutcome = synchronized(lock) {
        val current = balanceOf(userId)
        when {
            userId to idempotencyKey in appliedKeys ->
                com.dangerfield.cards.server.domain.ApplyOutcome.Applied(current, wasAlreadyApplied = true)
            current + delta < 0 ->
                com.dangerfield.cards.server.domain.ApplyOutcome.InsufficientChips(current)
            else -> {
                balances[userId] = current + delta
                appliedKeys += userId to idempotencyKey
                com.dangerfield.cards.server.domain.ApplyOutcome.Applied(current + delta, wasAlreadyApplied = false)
            }
        }
    }

    override suspend fun recentEvents(
        userId: UserId,
        limit: Int,
    ): List<com.dangerfield.cards.server.domain.WalletEvent> = emptyList()

    override suspend fun deleteAllForUser(userId: UserId) = synchronized(lock) {
        balances.remove(userId)
        appliedKeys.removeAll { it.first == userId }
        Unit
    }
}

/**
 * In-memory escrow for the harness — moves real chips through [FakeWallets] with
 * the same keyed semantics as the production engine, so buy-in at deal and
 * cash-out on leave reconcile the wallet across a full game.
 */
class FakeTableSessions(
    private val wallets: FakeWallets,
) : com.dangerfield.cards.server.domain.TableSessionService {
    private val lock = Any()
    private data class Active(val id: UUID, val roomCode: String, val buyIn: Long, var rebuys: Int)
    private val active = mutableMapOf<UserId, Active>()

    /** Open session probe — true between sitDown and cashOut. */
    fun isActive(userId: UserId): Boolean = synchronized(lock) { userId in active }

    /**
     * Every still-open session as a [com.dangerfield.cards.server.domain.TableSession]
     * row — the harness mirror of `TableSessionRepository.listActive()` the boot
     * recovery sweep fans out over. Lets a crash-recovery test drive the REAL
     * [com.dangerfield.cards.server.data.DefaultTableSessionRecoverySweep] over this
     * fake rather than re-deriving its logic (MP-13).
     */
    fun listActiveSessions(): List<com.dangerfield.cards.server.domain.TableSession> =
        synchronized(lock) { active.toMap() }.map { (userId, a) ->
            com.dangerfield.cards.server.domain.TableSession(
                sessionId = a.id,
                userId = userId,
                roomCode = a.roomCode,
                stakeTier = "casual",
                buyIn = a.buyIn,
                rebuyCount = a.rebuys,
                status = com.dangerfield.cards.server.domain.TableSessionStatus.Open,
                openedAt = Instant.fromEpochMilliseconds(0),
                closedAt = null,
            )
        }

    override suspend fun sitDown(
        userId: UserId,
        roomCode: String,
        requestedBuyIn: Long,
        enforceEntryBar: Boolean,
        subsidized: Boolean,
    ): com.dangerfield.cards.server.domain.SitDownResult {
        // The harness only ever sits valid (≥ floor) buy-ins, so unlike the real
        // service this fake skips the MIN_BUY_IN coercion and charges as-requested.
        val buyIn = requestedBuyIn
        synchronized(lock) { active[userId] }?.let {
            return com.dangerfield.cards.server.domain.SitDownResult.AlreadyAtTable(it.roomCode)
        }
        val balance = wallets.findOrCreate(userId).balance
        if (enforceEntryBar && balance < buyIn * 4) {
            return com.dangerfield.cards.server.domain.SitDownResult.BelowEntryBar(balance, buyIn * 4)
        }
        val id = UUID.randomUUID()
        return when (val o = wallets.apply(userId, "table:$id:buyin", -buyIn, "mp_buyin")) {
            is com.dangerfield.cards.server.domain.ApplyOutcome.InsufficientChips ->
                com.dangerfield.cards.server.domain.SitDownResult.InsufficientChips(o.balance)
            is com.dangerfield.cards.server.domain.ApplyOutcome.Applied -> {
                synchronized(lock) { active[userId] = Active(id, roomCode, buyIn, 0) }
                com.dangerfield.cards.server.domain.SitDownResult.Funded(id, buyIn, o.balance)
            }
        }
    }

    override suspend fun rebuy(
        userId: UserId,
        enforceEntryBar: Boolean,
    ): com.dangerfield.cards.server.domain.RebuyResult {
        val s = synchronized(lock) { active[userId] }
            ?: return com.dangerfield.cards.server.domain.RebuyResult.NoActiveSession
        val n = synchronized(lock) { ++s.rebuys }
        return when (val o = wallets.apply(userId, "table:${s.id}:rebuy:$n", -s.buyIn, "mp_rebuy")) {
            is com.dangerfield.cards.server.domain.ApplyOutcome.InsufficientChips ->
                com.dangerfield.cards.server.domain.RebuyResult.InsufficientChips(o.balance)
            is com.dangerfield.cards.server.domain.ApplyOutcome.Applied ->
                com.dangerfield.cards.server.domain.RebuyResult.ReboughtIn(s.buyIn, o.balance)
        }
    }

    override suspend fun cashOut(
        userId: UserId,
        finalStack: Long?,
    ): com.dangerfield.cards.server.domain.CashOutResult {
        val s = synchronized(lock) { active.remove(userId) }
            ?: return com.dangerfield.cards.server.domain.CashOutResult.NoActiveSession
        val refund = (finalStack ?: s.buyIn * (1 + s.rebuys)).coerceAtLeast(0L)
        val o = wallets.apply(userId, "table:${s.id}:cashout", refund, "mp_cashout")
            as com.dangerfield.cards.server.domain.ApplyOutcome.Applied
        return com.dangerfield.cards.server.domain.CashOutResult.CashedOut(refund, o.balance)
    }

    override suspend fun activeUsersInRoom(roomCode: String): List<UserId> =
        synchronized(lock) { active.filterValues { it.roomCode == roomCode }.keys.toList() }

    override suspend fun subsidyBudget(
        userId: UserId,
    ): com.dangerfield.cards.server.domain.SubsidyBudget =
        com.dangerfield.cards.server.domain.SubsidyBudget(
            grantedToday = 0L,
            cap = SUBSIDY_CAP,
            remaining = SUBSIDY_CAP,
        )

    private companion object {
        const val SUBSIDY_CAP = 25_000L
    }
}

/**
 * Thin [com.dangerfield.cards.server.domain.TableSessionRepository] over
 * [FakeTableSessions] — only [listActive] is real, the rest are unused by the
 * recovery sweep and error if touched. Lets the harness feed the production
 * [DefaultTableSessionRecoverySweep] its open-session list (MP-13).
 */
private class HarnessTableSessionRepository(
    private val sessions: FakeTableSessions,
) : com.dangerfield.cards.server.domain.TableSessionRepository {
    override suspend fun listActive(): List<com.dangerfield.cards.server.domain.TableSession> =
        sessions.listActiveSessions()

    override suspend fun find(sessionId: UUID) = error("unused in the integration harness")
    override suspend fun findActiveForUser(userId: UserId) = error("unused in the integration harness")
    override suspend fun findActiveByRoom(roomCode: String) = error("unused in the integration harness")
    override suspend fun markClosing(sessionId: UUID) = error("unused in the integration harness")
    override suspend fun markClosed(sessionId: UUID) = error("unused in the integration harness")
    override suspend fun incrementRebuy(sessionId: UUID) = error("unused in the integration harness")
    override suspend fun recordSubsidyGranted(sessionId: UUID, amount: Long) =
        error("unused in the integration harness")
    override suspend fun subsidyGrantedSince(userId: UserId, since: Instant) =
        error("unused in the integration harness")
    override suspend fun deleteAllForUser(userId: UserId) = error("unused in the integration harness")
}

/** No-op progression source — the integration harness doesn't seat opponent levels. */
private object FakeProgression : com.dangerfield.cards.server.domain.ProgressionRepository {
    override suspend fun findOrCreateResult(userId: UserId) = error("unused in the integration harness")
    override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserProgression? = null
    override suspend fun applyXp(
        userId: UserId,
        idempotencyKey: String,
        deltaXp: Long,
        source: String,
        mode: String,
        handId: String?,
        wasBoosted: Boolean,
    ) = error("unused in the integration harness")
    override suspend fun recentEvents(
        userId: UserId,
        limit: Int,
    ): List<com.dangerfield.cards.server.domain.XpEvent> = emptyList()
    override suspend fun deleteAllForUser(userId: UserId) = Unit
}
