package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.IntentResult
import com.dangerfield.cards.server.game.SeatOccupant
import com.dangerfield.cards.server.game.stackFor
import java.util.UUID
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.SpanAttrs
import com.dangerfield.cards.server.plugins.userId
import com.dangerfield.cards.server.plugins.withSpan
import io.ktor.server.auth.authenticate
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.dangerfield.cards.libraries.gameplay.scrubbedFor

/**
 * `GET /v1/rooms/{code}/socket` (WebSocket upgrade) — the per-room
 * presence + multiplayer-gameplay channel.
 *
 * Auth: same Supabase JWT as HTTP routes (Ktor's `authenticate` block
 * works across the upgrade). Membership: the caller MUST already be a
 * member via POST /v1/rooms/{code}/join — we don't implicit-join here
 * because join is the spot where seat-allocation lives and we want
 * mutations to flow through one path.
 *
 * Connection lifecycle:
 *  1. Upgrade succeeds → markConnected(true) → broadcast Snapshot to
 *     this socket → enter the per-room flow subscription.
 *  2. Every room mutation (anyone joins/leaves/connects/disconnects)
 *     fans out to every subscriber as Snapshot + the matching delta
 *     event (MemberJoined / MemberLeft / MemberPresenceChanged).
 *  3. Socket dies (clean close OR ping timeout) → markConnected(false).
 *     Seat is held; other clients see the presence flip. The user can
 *     reopen another socket to resume — same userId, same seat.
 *
 * Reconnect grace: on disconnect we stamp the member with
 * `disconnectedAt = now` and schedule a per-member reaper on the
 * Application's coroutine scope — `delay(reaperGrace)` then
 * [RoomService.reapIfStillDisconnected] with the captured stamp. If the
 * user reconnects (stamp cleared) or re-drops (stamp refreshed) the
 * original reaper short-circuits to a no-op and the fresh disconnect
 * schedules its own timer. Same broadcast machinery handles the reap's
 * effect: the room flow re-emits and subscribers see a `member_left`
 * delta for the freed seat.
 *
 * Delta computation: we keep the previous snapshot per-subscription
 * and diff against the new one to emit the right delta events. The
 * Snapshot itself is always sent first so clients have a fallback
 * even if they miss a delta during flaky network.
 *
 * Game frames (added with server-authoritative multiplayer):
 *  - A second publisher subscribes to the [GameSessionRegistry] for
 *    this room. The moment the host's `StartHand` creates a session,
 *    every connected client begins receiving personalized
 *    [RoomSocketEventDto.GameStateSnapshot] frames (scrubbed via
 *    [scrubbedFor] using the viewer's own seat index) and a raw
 *    [RoomSocketEventDto.GameEventOccurred] stream for animations.
 *  - The drain loop now decodes client→server [RoomClientFrame]s
 *    (StartHand / SubmitIntent / RequestNextHand) and dispatches them
 *    into the registry. Each frame ships back an
 *    [RoomSocketEventDto.IntentAck] keyed by clientNonce so callers can
 *    correlate retries / surface rejection reasons.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Route.roomSocketRoutes(
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
    wallets: WalletRepository,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    reaperGrace: Duration = DEFAULT_REAPER_GRACE,
) {
    val app = application
    authenticate(SUPABASE_JWT_AUTH) {
        webSocket("/v1/rooms/{code}/socket") {
            val code = call.parameters["code"]?.uppercase()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing code"))
            val userId = call.userId()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))

            val current = rooms.find(code)
            if (current == null || current.memberFor(userId) == null) {
                // Not a member yet — must POST /join first. We don't
                // implicit-join here because the seat allocator lives in
                // join() and we want mutations to flow through one path.
                // Info: a refused join is the backend half of "I couldn't get
                // into the game"; carries session_id via MDC for correlation.
                LoggerFactory.getLogger("RoomSocket")
                    .info("Socket refused: room=$code user=$userId not a member (join first)")
                return@webSocket close(
                    CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "not a member of this room"),
                )
            }

            val flow = rooms.observe(code) ?: run {
                LoggerFactory.getLogger("RoomSocket")
                    .info("Socket refused: room=$code not found (user=$userId)")
                return@webSocket close(
                    CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room not found"),
                )
            }

            rooms.markConnected(code, userId, connected = true)
            // Info: one line per socket open anchors "user joined room at T" in
            // Loki — the backend bookend to the client's connection breadcrumb.
            LoggerFactory.getLogger("RoomSocket").info("Socket connected: room=$code user=$userId")

            // Public tables have no human host to tap "Start", so the server
            // deals once enough players are present. Idempotent — only the first
            // connect that tips the table to playable actually starts a hand;
            // every later connect no-ops. A bot-filled fallback table is started
            // by the consent endpoint instead, not here.
            Catching {
                startServerDealtTableIfReady(code, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)
            }.onFailure {
                LoggerFactory.getLogger("RoomSocket")
                    .warn("Auto-start check failed for public room=$code", it)
            }

            // True mid-hand join: a searcher matched into a table that's already
            // playing connects here as a spectator of the live hand and is queued
            // to be dealt in at the next boundary. No-op for the players already
            // in the hand (reconnects) and for tables still in the lobby.
            Catching {
                queueMidHandJoinerIfNeeded(code, userId, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)
            }.onFailure {
                LoggerFactory.getLogger("RoomSocket")
                    .warn("Mid-hand join queue failed for room=$code user=$userId", it)
            }

            // Hydrate from the durable snapshot before the game publisher
            // subscribes. Without this, a client reconnecting after a
            // server restart sees the lobby snapshot but no game-state
            // frames until *someone* submits an intent — applyIntent /
            // requestNextHand both route through findOrHydrate, so the
            // first action would rehydrate, but a player who reconnects
            // mid-hand and just wants to watch their turn play out would
            // be staring at an empty table. Best-effort: a snapshot DB
            // failure logs but doesn't block the lobby socket from
            // working — the next intent will retry.
            Catching { gameSessions.findOrHydrate(code) }
                .onFailure {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Snapshot hydrate failed for room=$code user=$userId", it)
                }

            // This socket's recipient id — stamped on every outbound
            // ws_send span so the fan-out is queryable per recipient.
            val userIdString = userId.value.toString()

            // The room-flow collector runs in a child coroutine so we
            // can independently watch [incoming] for the close signal.
            // Without this split, the collector blocks indefinitely on
            // a quiet room (no upstream emissions == no chance to
            // notice the socket died), and the finally block never
            // runs to flip isConnected back.
            val publisher = launch {
                try {
                    // scan() lets us diff the previous snapshot against
                    // the new one each tick. distinctUntilChanged
                    // suppresses idempotent re-emits.
                    flow.distinctUntilChanged()
                        .scan<Room, Pair<Room?, Room>?>(null) { acc, next ->
                            val previous = acc?.second
                            previous to next
                        }
                        .collect { pair ->
                            if (pair == null) return@collect
                            val (previous, next) = pair
                            if (previous == null) {
                                sendTraced(RoomSocketEventDto.Snapshot(next.toDto()), code, userIdString)
                            } else {
                                // Order: Snapshot first (always-correct
                                // state) then any deltas the client can
                                // use for toasts / animations.
                                sendTraced(RoomSocketEventDto.Snapshot(next.toDto()), code, userIdString)
                                val deltas = diffDeltas(previous, next)
                                deltas.forEach { sendTraced(it, code, userIdString) }
                                // A member leaving (explicit /leave or a reaped
                                // disconnect — both surface as MemberLeft) cashes
                                // their stack back to their wallet, then folds their
                                // seat out of any live hand so the table doesn't
                                // stall on a gone player. Both are keyed/idempotent,
                                // so the per-subscriber duplicate calls are harmless
                                // (cashOut credits at most once; a bot or never-
                                // funded leaver is a no-op). Pot chips already
                                // committed are forfeit — stackFor excludes them.
                                deltas.filterIsInstance<RoomSocketEventDto.MemberLeft>().forEach { left ->
                                    val leftUserId = runCatching { UserId(UUID.fromString(left.userId)) }.getOrNull()
                                    if (leftUserId != null) {
                                        val stack = gameSessions.peek(code)?.state?.value?.stackFor(leftUserId)
                                        Catching { tableSessions.cashOut(leftUserId, stack) }
                                            .onFailure { e ->
                                                LoggerFactory.getLogger("RoomSocket")
                                                    .warn("cashOut failed for room=$code user=${left.userId}", e)
                                            }
                                    }
                                    // Drop them from the mid-hand-join queue if they
                                    // left while still waiting to be dealt in, so the
                                    // next hand never seats a gone player.
                                    Catching { gameSessions.dequeueJoiner(code, left.userId) }
                                    // Permanently drop them from future hands so their
                                    // just-vacated seat isn't re-dealt next hand (the
                                    // long-standing "ghost seat" bug). forfeitSeat folds
                                    // them out of the *current* hand; removePlayer keeps
                                    // them out of the *next* one.
                                    Catching { gameSessions.removePlayer(code, left.userId) }
                                    Catching { gameSessions.forfeitSeat(code, left.userId) }
                                        .onFailure { e ->
                                            LoggerFactory.getLogger("RoomSocket")
                                                .warn("forfeitSeat failed for room=$code user=${left.userId}", e)
                                        }
                                }
                            }
                        }
                } catch (_: CancellationException) {
                    // Expected when the outer loop cancels us on close.
                } catch (e: Throwable) {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Socket for room=$code user=$userId died publishing", e)
                }
            }

            // Game-state publisher. Subscribes to the per-room
            // GameSessionRegistry stream; flatMapLatest swaps in the
            // session's flows the moment a session appears (typically
            // right after the host's StartHand) and resets if a session
            // is dropped + re-created. Each emit looks up the viewer's
            // seat from the live state by playerId match — robust if
            // the seat index ever shifts (V1 it doesn't, but cheap to
            // be correct).
            val gamePublisher = launch {
                try {
                    gameSessions.observeSession(code)
                        .flatMapLatest { session ->
                            if (session == null) emptyFlow<OutboundGameFrame>()
                            else merge(
                                session.tracedState
                                    .filterNotNull()
                                    .map { traced ->
                                        val state = traced.state
                                        val viewerSeat = state.seats
                                            .firstOrNull { it.playerId == userIdString }
                                            ?.index ?: -1
                                        OutboundGameFrame(
                                            RoomSocketEventDto.GameStateSnapshot(
                                                state.scrubbedFor(viewerSeat),
                                            ),
                                            link = traced.originSpanContext.takeIf { it.isValid },
                                        )
                                    },
                                session.events.map { traced ->
                                    OutboundGameFrame(
                                        RoomSocketEventDto.GameEventOccurred(
                                            seq = traced.event.sequence,
                                            event = traced.event,
                                        ),
                                        link = traced.originSpanContext.takeIf { it.isValid },
                                    )
                                },
                                // Ephemeral table emotes — fanned out to
                                // every socket with no span link (they don't
                                // originate from an intent / state mutation).
                                session.emojiBlasts.map { blast ->
                                    OutboundGameFrame(
                                        RoomSocketEventDto.EmojiBlast(
                                            seatIndex = blast.seatIndex,
                                            emoji = blast.emoji,
                                        ),
                                        link = null,
                                    )
                                },
                            )
                        }
                        .collect { sendTraced(it.event, code, userIdString, link = it.link) }
                } catch (_: CancellationException) {
                    // Expected on close.
                } catch (e: Throwable) {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Game publisher for room=$code user=$userId died", e)
                }
            }

            // "Bots step aside for humans." On a public table that a searcher
            // rescued (now ≥2 humans), trim one bot at each hand boundary so the
            // table converges to an all-human game. Watches the session for the
            // moment a hand goes Complete and, once per hand (keyed by hand
            // number), asks the room to drop a bot. trimBotForNewHumans no-ops
            // unless this is a public table with ≥2 humans + a bot, so a private
            // game or a still-lonely bot table is never touched. The trimmed bot
            // is also dropped from the live session so the bot driver's next-hand
            // deal (a ~4s settle away) doesn't re-seat it. Idempotent + harmless
            // to run from every subscriber's socket.
            val botTrimmer = launch {
                try {
                    gameSessions.observeSession(code)
                        .flatMapLatest { session ->
                            session?.state?.filterNotNull() ?: emptyFlow()
                        }
                        .map { state -> state.handNumber.takeIf { state.street == BettingRound.Complete } }
                        .distinctUntilChanged()
                        .filterNotNull()
                        .collect { handNumber ->
                            val trimmed = Catching { rooms.trimBotForNewHumans(code, handNumber) }
                                .onFailure { e ->
                                    LoggerFactory.getLogger("RoomSocket")
                                        .warn("bot trim failed for room=$code hand=$handNumber", e)
                                }
                                .getOrNull()
                            if (trimmed != null) {
                                Catching { gameSessions.removePlayer(code, trimmed.value.toString()) }
                            }
                        }
                } catch (_: CancellationException) {
                    // Expected on close.
                } catch (e: Throwable) {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Bot trimmer for room=$code user=$userId died", e)
                }
            }

            try {
                // Drain incoming. We now decode client-bound game
                // frames (StartHand / SubmitIntent / RequestNextHand)
                // and dispatch into the registry. Unknown / malformed
                // frames are logged and dropped — same forward-compat
                // policy as the client's decode-and-drop on the
                // server-bound side.
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val raw = frame.readText()
                    val clientFrame = try {
                        JSON.decodeFromString(RoomClientFrame.serializer(), raw)
                    } catch (e: Throwable) {
                        LoggerFactory.getLogger("RoomSocket")
                            .warn("Bad client frame from room=$code user=$userId: ${e.message}")
                        continue
                    }
                    val ack = handleClientFrame(
                        code = code,
                        userId = userId,
                        frame = clientFrame,
                        rooms = rooms,
                        gameSessions = gameSessions,
                        tableSessions = tableSessions,
                        equipmentRepository = equipmentRepository,
                        progressionRepository = progressionRepository,
                        wallets = wallets,
                    )
                    sendJson(ack)
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal close path.
            } catch (_: CancellationException) {
                throw CancellationException("session cancelled")
            } catch (e: Throwable) {
                LoggerFactory.getLogger("RoomSocket")
                    .warn("Socket for room=$code user=$userId died reading", e)
            } finally {
                publisher.cancel()
                gamePublisher.cancel()
                botTrimmer.cancel()
                // Mark disconnected regardless of close cause. Capture
                // the stamp the service just set so the reaper can
                // cross-check that the user didn't reconnect (or
                // re-drop) during the grace window.
                val afterDisconnect = rooms.markConnected(code, userId, connected = false)
                val droppedAt = afterDisconnect?.memberFor(userId)?.disconnectedAt
                if (droppedAt != null) {
                    // Grace depends on what kind of room this is. A forming
                    // *Public* table (Lobby) frees an abandoned seat fast so a
                    // searcher who quits the Searching screen doesn't leave a
                    // ghost inflating "found N players"; everything else — a live
                    // hand (Playing), a private room, or an Open room whose lone
                    // member is the waiting host — keeps the full window so a
                    // brief cellular blip never loses the seat.
                    val initialGrace = effectiveReaperGrace(afterDisconnect, reaperGrace)
                    app.launch {
                        try {
                            delay(initialGrace)
                            // If we only waited the short forming window, the table may have
                            // dealt a hand meanwhile — it's Playing now and the seat must get
                            // the full window, never a 25s reap out from under a live hand. So
                            // when the short grace elapses we re-read the room and wait out any
                            // remainder the longer window now owes. A room that was already
                            // non-forming at disconnect waited its full grace up front (and
                            // keeps the injectable window tests rely on), so we skip this.
                            if (initialGrace == FORMING_PUBLIC_REAPER_GRACE) {
                                val remaining = graceRemainderAfterShortWindow(rooms.find(code), reaperGrace)
                                if (remaining.isPositive()) delay(remaining)
                            }
                            rooms.reapIfStillDisconnected(code, userId, droppedAt)
                        } catch (_: CancellationException) {
                            // Server shutdown — this in-process timer dies with it. The
                            // member's seat (and disconnectedAt stamp) is durable now, so
                            // the admin /sweep-rooms cron is the backstop that reclaims it
                            // after a restart; this timer is just the fast path.
                        } catch (e: Throwable) {
                            LoggerFactory.getLogger("RoomSocket")
                                .warn("Reaper for room=$code user=$userId failed", e)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Routes a single decoded [RoomClientFrame] into the right registry
 * call and packages the result into an [RoomSocketEventDto.IntentAck].
 * Pulled out of the route lambda so the dispatch table reads top-down.
 */
private suspend fun handleClientFrame(
    code: String,
    userId: UserId,
    frame: RoomClientFrame,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
    wallets: WalletRepository,
): RoomSocketEventDto.IntentAck {
    val result: IntentResult = when (frame) {
        is RoomClientFrame.StartHand ->
            handleStartHand(code, userId, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)
        is RoomClientFrame.SubmitIntent -> withSpan(
            name = "submit_intent",
            configure = {
                setAttribute(SpanAttrs.FrameType, "submit_intent")
                setAttribute(SpanAttrs.RoomCode, code)
                setAttribute(SpanAttrs.UserId, userId.value.toString())
                setAttribute(SpanAttrs.ClientNonce, frame.clientNonce)
                setAttribute(SpanAttrs.IntentType, frame.intent::class.simpleName ?: "Unknown")
            },
        ) {
            gameSessions.applyIntent(
                code = code,
                actorUserId = userId.value.toString(),
                intent = frame.intent,
                clientNonce = frame.clientNonce,
            ).also { recordIntentOutcome(it) }
        }
        is RoomClientFrame.RequestNextHand -> gameSessions.requestNextHand(
            code = code,
            actorUserId = userId.value.toString(),
            clientNonce = frame.clientNonce,
        )
        is RoomClientFrame.Rebuy -> withSpan(
            name = "rebuy",
            configure = {
                setAttribute(SpanAttrs.FrameType, "rebuy")
                setAttribute(SpanAttrs.RoomCode, code)
                setAttribute(SpanAttrs.UserId, userId.value.toString())
                setAttribute(SpanAttrs.ClientNonce, frame.clientNonce)
            },
        ) {
            handleRebuy(code, userId, frame.clientNonce, rooms, gameSessions, tableSessions, wallets)
                .also { recordIntentOutcome(it) }
        }
        is RoomClientFrame.SendEmoji -> gameSessions.broadcastEmoji(
            code = code,
            actorUserId = userId.value.toString(),
            emoji = frame.emoji,
        )
    }
    return RoomSocketEventDto.IntentAck(
        clientNonce = frame.clientNonce,
        accepted = result is IntentResult.Accepted,
        error = (result as? IntentResult.Rejected)?.reason,
    )
}

/**
 * Stamps the active span (the `submit_intent` root started above) with
 * the outcome of the registry call. Decoupled from the result encoding
 * so attribute names stay consistent regardless of how IntentAck
 * evolves.
 */
private fun recordIntentOutcome(result: IntentResult) {
    val span = Span.current()
    span.setAttribute(SpanAttrs.Accepted, result is IntentResult.Accepted)
    if (result is IntentResult.Rejected) {
        span.setAttribute(SpanAttrs.RejectionReason, result.reason)
    }
}

/**
 * Buy a busted seat back into the table — the escrow top-up that pairs with the
 * engine refill.
 *
 * Cross-domain orchestration lives here, not in [com.dangerfield.cards.server.game.GameSession]
 * (which stays pure-engine):
 *
 *  1. Pre-check off the live session so an obviously-invalid rebuy (no completed
 *     hand, caller not seated, seat not busted) is rejected *without* touching
 *     the ledger. [com.dangerfield.cards.server.game.GameSession.rebuy] re-checks
 *     under its lock — this just keeps the common rejects off the wallet.
 *  2. [com.dangerfield.cards.server.domain.TableSessionService.rebuy] debits one
 *     more buy-in (keyed `table:{sessionId}:rebuy:{n}`, idempotent) and bumps the
 *     session's rebuy counter, re-applying the entry bar for public tables.
 *  3. Refill the engine seat. The debit and refill aren't one transaction, so if
 *     the refill loses a race against the next hand we compensate with a keyed
 *     refund. (The rebuy counter stays bumped — the next real rebuy just uses
 *     n+1; chips net to zero, which is what matters.)
 */
private suspend fun handleRebuy(
    code: String,
    userId: UserId,
    clientNonce: String,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    wallets: WalletRepository,
): IntentResult {
    val room = rooms.find(code) ?: return IntentResult.Rejected("room not found")
    val buyIn = room.settings.startingStack

    val state = gameSessions.peek(code)?.state?.value
    if (state == null || state.street != BettingRound.Complete) {
        return IntentResult.Rejected("no completed hand to rebuy into")
    }
    val seat = state.seats.firstOrNull { it.playerId == userId.value.toString() }
        ?: return IntentResult.Rejected("not seated in this room")
    if (seat.stack > 0) return IntentResult.Rejected("seat is not busted")

    when (tableSessions.rebuy(userId, enforceEntryBar = room.visibility != RoomVisibility.Private)) {
        is com.dangerfield.cards.server.domain.RebuyResult.NoActiveSession ->
            return IntentResult.Rejected("no active table session to rebuy into")
        is com.dangerfield.cards.server.domain.RebuyResult.BelowEntryBar ->
            return IntentResult.Rejected("below entry bar")
        is com.dangerfield.cards.server.domain.RebuyResult.InsufficientChips ->
            return IntentResult.Rejected("insufficient chips")
        is com.dangerfield.cards.server.domain.RebuyResult.ReboughtIn -> Unit
    }

    val result = gameSessions.rebuy(code, userId.value.toString(), clientNonce)
    if (result is IntentResult.Rejected) {
        // Refill lost a race after the escrow debit — credit the buy-in back under
        // a distinct key so it isn't deduped against the table:rebuy debit.
        wallets.apply(
            userId = userId,
            idempotencyKey = "mp_rebuy_refund:$code:$clientNonce",
            delta = +buyIn,
            reason = "mp_rebuy_refund",
        )
    }
    return result
}

/**
 * Host-gated start-hand handler. Validates host, funds + deals the hand at the
 * room's stakes, and (on success) flips the room to Playing so the lobby
 * snapshot's `status` change cascades to all subscribers.
 */
private suspend fun handleStartHand(
    code: String,
    userId: UserId,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): IntentResult {
    val room = rooms.find(code)
        ?: return IntentResult.Rejected("room not found")
    // Open + Public tables deal themselves once enough players are present — a
    // host StartHand on one would race the auto-deal. The client hides Start for
    // these, so this is belt-and-suspenders; reject it rather than double-deal.
    if (room.visibility != RoomVisibility.Private) {
        return IntentResult.Rejected("server deals this table")
    }
    if (room.hostUserId != userId) {
        return IntentResult.Rejected("only the host can start the hand")
    }
    return dealFundedHand(room, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)
}

/**
 * Fund every affordable human seat (move the room's buy-in wallet → table
 * stack), then deal the hand with the funded humans + the table's bots. This is
 * the one place a real-stakes hand begins, shared by the host-driven start and
 * the server-driven public-table start.
 *
 * A human who can't cover the buy-in (or who's already escrowed at another
 * table) is simply left out of the deal — never seated unfunded, so the engine
 * stack always matches escrowed chips. If fewer than two funded seats remain we
 * cash any humans this call just funded back out and refuse — never debit for a
 * hand that doesn't happen. Bots are never funded: they have no wallet, only an
 * engine stack.
 */
private suspend fun dealFundedHand(
    room: Room,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): IntentResult {
    // Escrow moves real chips on a real-stakes (majority-human) table OR a public
    // disclosed-bot table (a lone human vs labelled bots — the house funds the
    // win, capped per day). Everything else — bot-stacked (`2H + 4B`) or a private
    // practice bot game — is practice: no buy-in, no cash-out. The bot-stacked
    // exclusion closes the collusion farm; the daily cap fences the subsidy.
    val funded = if (chipsAreReal(room)) {
        fundAndBuildOccupants(room, tableSessions, equipmentRepository, progressionRepository)
    } else {
        FundedStart(
            occupants = room.members.map { seatOccupantFor(it, equipmentRepository, progressionRepository) },
            newlyFunded = emptyList(),
        )
    }
    // Never deal a hand with no human — e.g. the lone human on a subsidised bot
    // table hit their daily cap (or couldn't be funded), leaving only bots.
    if (funded.occupants.none { !it.isBot }) {
        funded.newlyFunded.forEach { Catching { tableSessions.cashOut(it, finalStack = null) } }
        return IntentResult.Rejected("no funded human to deal in")
    }
    if (funded.occupants.size < 2) {
        // Not enough players could fund — refund anyone we just debited (they sat
        // but no hand dealt → full refund) and don't start.
        funded.newlyFunded.forEach { Catching { tableSessions.cashOut(it, finalStack = null) } }
        return IntentResult.Rejected("need at least 2 funded players to start")
    }
    val result = gameSessions.startHand(room.code, funded.occupants, room.settings)
    if (result is IntentResult.Accepted) {
        rooms.markPlaying(room.code)
    }
    // A Rejected here is only "hand already in progress" (occupant count is
    // pre-checked) — a concurrent start already dealt, and our funded humans are
    // seated in THAT hand, so we must NOT refund them. sitDown's double-spend
    // guard already collapsed the racing debits to one per player.
    return result
}

/** True when this table moves real chips: a real-stakes human table or the disclosed-bot subsidy. */
private fun chipsAreReal(room: Room): Boolean = isRealStakesTable(room) || isSubsidizedBotTable(room)

/**
 * A real-stakes table moves real chips through escrow. The floor is always **≥ 2
 * humans**, but the bot-tolerance differs by how the table was formed:
 *
 *  - **Public** (matchmaker-formed): two or more humans = real stakes, full stop,
 *    *even with bots still seated*. A public table's humans are matched at random
 *    — they can't deliberately co-sit to farm bots — and any bots present are
 *    matchmaking placeholders that trim out one per hand ([RoomService.trimBotForNewHumans])
 *    until the table is all-human. So the moment a searcher rescues a lonely
 *    player, both play for real while the bots bow out.
 *  - **Open / Private** (human-created): keep the stricter **humans ≥ bots**
 *    floor. Friends *can* deliberately stack bots to co-farm here, so a
 *    bot-stacked (`2H + 4B`) table stays practice — the collusion guard.
 *
 * Mirrors the client's `MultiplayerCredit.qualifies` (product-spec §5.4) so the
 * server's "do chips move" and the client's "does this count for MP credit" agree.
 */
private fun isRealStakesTable(room: Room): Boolean {
    val humans = room.members.count { !it.isBot }
    val bots = room.members.count { it.isBot }
    return when (room.visibility) {
        RoomVisibility.Public -> humans >= 2
        RoomVisibility.Open, RoomVisibility.Private -> humans >= 2 && humans >= bots
    }
}

/**
 * A disclosed-bot subsidy table: a **public** matchmaker table with exactly one
 * human playing labelled bots (the "play with bots" fallback). The house funds
 * the win here, capped per player per day. Restricted to one human so two players
 * can't co-farm bots under the subsidy (that path is [isRealStakesTable]'s
 * bot-stacked exclusion → practice).
 */
private fun isSubsidizedBotTable(room: Room): Boolean {
    if (room.visibility != RoomVisibility.Public) return false
    val humans = room.members.count { !it.isBot }
    val bots = room.members.count { it.isBot }
    return humans == 1 && bots >= 1
}

/** Occupants to deal + the userIds this call newly debited (to refund on abort). */
private data class FundedStart(val occupants: List<SeatOccupant>, val newlyFunded: List<UserId>)

private suspend fun fundAndBuildOccupants(
    room: Room,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): FundedStart {
    val subsidized = isSubsidizedBotTable(room)
    // The 25% entry bar is a public-matchmaking guard; private friend games AND
    // subsidised bot tables skip it (the daily cap is the bot-table guard, and a
    // new player should be able to afford the bots).
    val enforceEntryBar = room.visibility != RoomVisibility.Private && !subsidized
    val occupants = mutableListOf<SeatOccupant>()
    val newlyFunded = mutableListOf<UserId>()
    for (member in room.members) {
        if (member.bot != null) {
            occupants += seatOccupantFor(member, equipmentRepository, progressionRepository)
            continue
        }
        when (val sit = tableSessions.sitDown(member.userId, room.code, room.buyIn, enforceEntryBar, subsidized)) {
            is com.dangerfield.cards.server.domain.SitDownResult.Funded -> {
                newlyFunded += member.userId
                occupants += seatOccupantFor(member, equipmentRepository, progressionRepository)
            }
            is com.dangerfield.cards.server.domain.SitDownResult.AlreadyAtTable ->
                // Already escrowed here (a re-deal / concurrent start) → seat them.
                // Escrowed at a DIFFERENT room → leave them out of this deal.
                if (sit.roomCode == room.code) {
                    occupants += seatOccupantFor(member, equipmentRepository, progressionRepository)
                }
            // Can't afford the buy-in, or hit today's bot-subsidy cap → not dealt
            // in (the client surfaces the right upsell / "come back tomorrow").
            is com.dangerfield.cards.server.domain.SitDownResult.BelowEntryBar,
            is com.dangerfield.cards.server.domain.SitDownResult.SubsidyCapReached,
            is com.dangerfield.cards.server.domain.SitDownResult.InsufficientChips -> Unit
        }
    }
    return FundedStart(occupants, newlyFunded)
}

/**
 * Project a single room member into an engine [SeatOccupant]. Bots carry their
 * personality (so the server bot driver can play them) and no repo-looked-up
 * metadata; humans get their equipped badges/titles, avatar, and XP resolved
 * once here and frozen onto the Seat for the session.
 */
private suspend fun seatOccupantFor(
    member: com.dangerfield.cards.server.domain.RoomMember,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): SeatOccupant {
    val botSeat = member.bot
    return if (botSeat != null) {
        SeatOccupant(
            seatIndex = member.seatIndex,
            userId = member.userId.value.toString(),
            displayName = member.displayName,
            isBot = true,
            avatarEmoji = member.avatarEmoji.takeIf { it.isNotBlank() },
            avatarBackgroundColor = member.avatarBackgroundColor,
            bot = botSeat,
        )
    } else {
        SeatOccupant(
            seatIndex = member.seatIndex,
            userId = member.userId.value.toString(),
            displayName = member.displayName,
            isBot = false,
            badgeProductIds = equipmentRepository.listEquipped(member.userId)
                .map { it.productId }
                .filter { it.startsWith("badge_") || it.startsWith("title_") },
            avatarEmoji = member.avatarEmoji.takeIf { it.isNotBlank() },
            avatarBackgroundColor = member.avatarBackgroundColor,
            xp = progressionRepository.find(member.userId)?.totalXp,
        )
    }
}

/**
 * Deal the first hand of a **server-dealt** table once it's playable — any
 * matchmaking-discoverable table (`Public` *or* `Open`) deals itself, since a
 * searcher matched into one shouldn't wait on a human to tap "Start". Idempotent
 * and safe to call on every socket connect + after a bot fallback: no-ops unless
 * the room is an Open/Public Lobby with no live session yet.
 *
 * `Open` is server-dealt too (owner decision 2026-06-23): "open to anyone" means
 * "fill it and play", so it reuses the exact same auto-deal + auto-advance path
 * as a public table. Only `Private` (code-only friend games) waits for the host.
 *
 * The readiness gate is **at least two *present* seats** — a connected human or a
 * bot — so we never deal a table where everyone only joined over HTTP and no
 * socket is open yet (that would deal to nobody). Once the gate is met the deal
 * seats *all* current members: a member who reserved a seat via `find` but whose
 * socket isn't open yet is dealt in and holds their seat, auto-folded by the turn
 * timer until they (re)connect — the same treatment as any mid-game disconnect,
 * and correct since the seat is genuinely theirs.
 *
 * Returns [IntentResult] for logging; callers ignore the benign "not ready"
 * rejections.
 */
internal suspend fun startServerDealtTableIfReady(
    code: String,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): IntentResult {
    val room = rooms.find(code) ?: return IntentResult.Rejected("room not found")
    // Private rooms wait for the host; Open + Public are server-dealt.
    if (room.visibility == RoomVisibility.Private) return IntentResult.Rejected("private table is host-dealt")
    if (room.status != RoomStatus.Lobby) return IntentResult.Accepted // already dealt
    if (gameSessions.peek(code) != null) return IntentResult.Accepted // session already live
    val present = room.members.count { it.isBot || it.isConnected }
    if (present < 2) return IntentResult.Rejected("not enough present players")
    return dealFundedHand(room, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)
}

/**
 * Queue a member who connected mid-hand to be dealt in at the next hand boundary
 * (true mid-hand join). Does nothing unless the room is mid-game with a live
 * session and the caller isn't already holding a seat in it — so a player
 * reconnecting to their own live hand, a bot, or anyone on a lobby table is left
 * alone. On a real-stakes table the joiner's buy-in is escrowed now (so the seat
 * is funded the moment it's dealt); if they can't afford it they stay a
 * spectator and aren't queued. Should they leave before the next hand, the
 * leave path cashes them back out and drops them from the queue.
 */
private suspend fun queueMidHandJoinerIfNeeded(
    code: String,
    userId: UserId,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
) {
    val room = rooms.find(code) ?: return
    if (room.status != RoomStatus.Playing) return
    val session = gameSessions.peek(code) ?: return
    val member = room.memberFor(userId) ?: return
    if (member.bot != null) return
    // Already in the live hand (a reconnect) → nothing to seat.
    if (session.state.value?.seats?.any { it.playerId == userId.value.toString() } == true) return

    if (isRealStakesTable(room)) {
        when (tableSessions.sitDown(userId, code, room.buyIn, enforceEntryBar = room.visibility != RoomVisibility.Private)) {
            is com.dangerfield.cards.server.domain.SitDownResult.BelowEntryBar,
            is com.dangerfield.cards.server.domain.SitDownResult.InsufficientChips ->
                return // can't afford the buy-in → spectate, don't queue a seat
            else -> Unit // Funded, or already escrowed here → proceed
        }
    }
    gameSessions.queueJoiner(code, seatOccupantFor(member, equipmentRepository, progressionRepository))
}

/**
 * Default grace window before a disconnected member's seat is freed.
 * Short by design — V1 rooms are ephemeral and a blocked seat hurts
 * UX fast. Five minutes is enough for a typical reconnect on a flaky
 * cellular drop but short enough that an abandoned room is reusable
 * inside one hand of play.
 */
val DEFAULT_REAPER_GRACE: Duration = 5.minutes

/**
 * Grace for a *forming* matchmaker-created Public table (Lobby, hand not yet
 * dealt). Much shorter than [DEFAULT_REAPER_GRACE]: long enough to ride out the
 * find → socket-open handshake and a brief blip, short enough that abandoning
 * the Searching screen frees the seat fast so it never lingers as a ghost in
 * another searcher's "found N players". Once the hand deals (Playing) the full
 * window applies again — mid-hand reconnect is sacred.
 */
val FORMING_PUBLIC_REAPER_GRACE: Duration = 25.seconds

/**
 * The grace to apply for a member who just dropped from [room]. A forming
 * *Public* table (matchmaker-created, still in Lobby) gets the short
 * [FORMING_PUBLIC_REAPER_GRACE]; everything else — a live hand, a private room,
 * or an *Open* room — gets [default].
 *
 * Open is deliberately excluded even though it's matchmaking-eligible: a Public
 * table's lone member is a searcher whose ghost should free fast when they quit
 * the Searching screen, but an Open table's lone member is the host who created
 * the room and is *waiting* for players. Giving Open the 25s window would let the
 * reaper GC a host's table on a brief background (call, network blip) — the
 * classic "my table vanished" bug. The cost is that an abandoned stranger's seat
 * in an Open lobby lingers up to [default] (5 min), which we accept.
 *
 * Extracted so the policy is unit-testable without standing up a socket.
 */
internal fun effectiveReaperGrace(room: Room?, default: Duration): Duration {
    val forming = room != null && room.visibility == RoomVisibility.Public && room.status == RoomStatus.Lobby
    return if (forming) FORMING_PUBLIC_REAPER_GRACE else default
}

/**
 * Grace still owed after the reaper's initial [FORMING_PUBLIC_REAPER_GRACE] wait,
 * given the room's status *re-read at that mark*. The window a seat qualifies for
 * can change mid-grace: a forming table that deals a hand becomes [RoomStatus.Playing]
 * and must get the full window, never a fast reap out from under a live seat. A
 * still-forming table is done (returns zero, reap now); a now-playing (or private)
 * table owes the rest of [default]. Coerced non-negative so a custom [default]
 * shorter than the forming window (tests) can't yield a negative delay.
 */
internal fun graceRemainderAfterShortWindow(room: Room?, default: Duration): Duration =
    (effectiveReaperGrace(room, default) - FORMING_PUBLIC_REAPER_GRACE).coerceAtLeast(Duration.ZERO)

private val JSON = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

private suspend fun WebSocketServerSession.sendJson(event: RoomSocketEventDto) {
    send(Frame.Text(JSON.encodeToString(RoomSocketEventDto.serializer(), event)))
}

/**
 * [sendJson] wrapped in a `ws_send` span so the publisher fan-out — until
 * now the one untraced leg of the gameplay path — shows up in traces with
 * its own latency + error record per recipient.
 *
 * Fan-out here is implicit: there's no central broadcast loop, each
 * socket's own publisher collects the shared room / game flows, so the
 * natural granularity is one span per recipient per frame (`user.id` =
 * recipient).
 *
 * [link] ties the send back to the span that produced the frame. Both
 * gameplay legs carry the originating `state_mutate` / `start_hand` span
 * context — game events on their [TracedGameEvent] envelope, game-state
 * snapshots on [TracedState] — so a per-recipient `GameEventOccurred` or
 * `GameStateSnapshot` fan-out links back to the `submit_intent` that
 * triggered it instead of floating as a root span. The snapshot leg's
 * attribution is approximate (the conflated `StateFlow` may collapse
 * rapid mutations); see [TracedState]. Lobby snapshots pass `null`.
 */
private suspend fun WebSocketServerSession.sendTraced(
    event: RoomSocketEventDto,
    code: String,
    recipient: String,
    link: SpanContext? = null,
) = withSpan(
    name = "ws_send",
    configure = {
        setAttribute(SpanAttrs.FrameType, event::class.simpleName ?: "Unknown")
        setAttribute(SpanAttrs.RoomCode, code)
        setAttribute(SpanAttrs.UserId, recipient)
        if (link != null && link.isValid) addLink(link)
    },
) {
    sendJson(event)
}

/**
 * Pairs an outbound [RoomSocketEventDto] with the optional span context
 * to link its `ws_send` span to. Lets the game publisher merge the
 * unlinked state-snapshot leg and the linked game-event leg into one
 * collected flow without losing the per-frame link.
 */
private data class OutboundGameFrame(
    val event: RoomSocketEventDto,
    val link: SpanContext?,
)

/**
 * Returns the delta events that turn [previous] into [next]. Order:
 * presence changes first (cheap, frequent), then leaves (drop them
 * before render), then joins (add at the end).
 *
 * Renames / seat-shuffles aren't reported as deltas — those are
 * implicitly captured by the Snapshot that goes out alongside.
 *
 * `internal` so [`DiffDeltasTest`] can pin the contract directly — the
 * publisher loop's `scan { acc, next -> ... }` shape would require
 * driving the entire flow to assert on the deltas otherwise.
 */
internal fun diffDeltas(previous: Room, next: Room): List<RoomSocketEventDto> {
    val prevById = previous.members.associateBy { it.userId }
    val nextById = next.members.associateBy { it.userId }
    val out = mutableListOf<RoomSocketEventDto>()

    // Presence flips for members present in both snapshots.
    for ((id, nextMember) in nextById) {
        val prevMember = prevById[id] ?: continue
        if (prevMember.isConnected != nextMember.isConnected) {
            out += RoomSocketEventDto.MemberPresenceChanged(
                userId = id.value.toString(),
                isConnected = nextMember.isConnected,
            )
        }
    }
    // Leaves: anyone in previous but not next.
    for ((id, _) in prevById) {
        if (id !in nextById) {
            out += RoomSocketEventDto.MemberLeft(userId = id.value.toString())
        }
    }
    // Joins: anyone in next but not previous.
    for ((id, member) in nextById) {
        if (id !in prevById) {
            out += RoomSocketEventDto.MemberJoined(member = member.toDto())
        }
    }
    return out
}
