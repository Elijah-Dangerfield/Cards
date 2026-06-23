package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.IntentResult
import com.dangerfield.cards.server.game.SeatOccupant
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
                                // disconnect — both surface as MemberLeft) folds
                                // their seat out of any live hand so the table
                                // doesn't stall on a gone player. Idempotent, so
                                // the per-subscriber duplicate calls are harmless.
                                deltas.filterIsInstance<RoomSocketEventDto.MemberLeft>().forEach { left ->
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
                // Mark disconnected regardless of close cause. Capture
                // the stamp the service just set so the reaper can
                // cross-check that the user didn't reconnect (or
                // re-drop) during the grace window.
                val afterDisconnect = rooms.markConnected(code, userId, connected = false)
                val droppedAt = afterDisconnect?.memberFor(userId)?.disconnectedAt
                if (droppedAt != null) {
                    // Grace depends on what kind of room this is. A forming
                    // public/open table (Lobby) frees an abandoned seat fast so
                    // a searcher who quits the Searching screen doesn't leave a
                    // ghost inflating "found N players"; a live hand (Playing),
                    // public or private, keeps the full window so a mid-hand
                    // cellular blip never loses the seat.
                    val effectiveGrace = effectiveReaperGrace(afterDisconnect, reaperGrace)
                    app.launch {
                        try {
                            delay(effectiveGrace)
                            rooms.reapIfStillDisconnected(code, userId, droppedAt)
                        } catch (_: CancellationException) {
                            // Server shutdown — leave the member; the
                            // next process boot won't have them anyway
                            // (rooms are in-memory).
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
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
    wallets: WalletRepository,
): RoomSocketEventDto.IntentAck {
    val result: IntentResult = when (frame) {
        is RoomClientFrame.StartHand ->
            handleStartHand(code, userId, rooms, gameSessions, equipmentRepository, progressionRepository)
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
            handleRebuy(code, userId, frame.clientNonce, rooms, gameSessions, wallets)
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
 * Buy a busted seat back into the table.
 *
 * Cross-domain orchestration lives here, not in [com.dangerfield.cards.server.game.GameSession]
 * (which stays pure-engine): we debit the wallet by the room buy-in
 * ([RoomSettings.startingStack]) and only then refill the seat.
 *
 *  1. Pre-check off the live session so an obviously-invalid rebuy (no
 *     completed hand, caller not seated, seat not busted) is rejected
 *     *without* churning the ledger. [com.dangerfield.cards.server.game.GameSession.rebuy]
 *     re-checks the same conditions under its lock — this is just an
 *     optimization to keep the common reject cases off the wallet.
 *  2. Debit the wallet, idempotent by `clientNonce`, so a socket retry can't
 *     double-charge. Insufficient balance → reject, no refill.
 *  3. Refill the seat. The debit and refill aren't one transaction, so if the
 *     refill rejects (state raced between the pre-check and the lock) we
 *     compensate with a refund under a distinct idempotency key.
 */
private suspend fun handleRebuy(
    code: String,
    userId: UserId,
    clientNonce: String,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
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

    val debit = wallets.apply(
        userId = userId,
        idempotencyKey = "rebuy:$code:$clientNonce",
        delta = -buyIn,
        reason = "rebuy",
    )
    if (debit is ApplyOutcome.InsufficientChips) {
        return IntentResult.Rejected("insufficient chips")
    }

    val result = gameSessions.rebuy(code, userId.value.toString(), clientNonce)
    if (result is IntentResult.Rejected) {
        // Refill rejected after we already debited — give the chips back. The
        // refund key is distinct from the debit key so it isn't deduped against it.
        wallets.apply(
            userId = userId,
            idempotencyKey = "rebuy_refund:$code:$clientNonce",
            delta = +buyIn,
            reason = "rebuy_refund",
        )
    }
    return result
}

/**
 * Host-gated start-hand handler. Pulls the room, validates host,
 * builds occupants from the current member list, calls the registry,
 * and (on success) flips the room status to Playing so the lobby
 * snapshot's `status` change cascades to all subscribers.
 */
private suspend fun handleStartHand(
    code: String,
    userId: UserId,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    equipmentRepository: com.dangerfield.cards.server.domain.EquipmentRepository,
    progressionRepository: com.dangerfield.cards.server.domain.ProgressionRepository,
): IntentResult {
    val room = rooms.find(code)
        ?: return IntentResult.Rejected("room not found")
    if (room.hostUserId != userId) {
        return IntentResult.Rejected("only the host can start the hand")
    }
    val occupants = room.members.map { member ->
        val botSeat = member.bot
        if (botSeat != null) {
            // Bots carry no equipment / XP and aren't looked up in any repo;
            // their personality rides along so the server bot driver can play
            // them. The avatar is the reserved 🤖 only for a revealed bot.
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
                // Resolve the player's equipped badges/titles once, here at
                // hand-start — they ride the Seat to every opponent's client, which
                // resolves each id to display metadata from its own catalog.
                badgeProductIds = equipmentRepository.listEquipped(member.userId)
                    .map { it.productId }
                    .filter { it.startsWith("badge_") || it.startsWith("title_") },
                // Avatar was snapshotted from the profile at join; ride it onto
                // the Seat so opponents render the real avatar, not initials.
                avatarEmoji = member.avatarEmoji.takeIf { it.isNotBlank() },
                avatarBackgroundColor = member.avatarBackgroundColor,
                // Resolve XP once here too — it rides the Seat so opponents derive
                // the player's level client-side. Frozen per session (mirrors
                // badges): preserved across hands rather than re-resolved.
                xp = progressionRepository.find(member.userId)?.totalXp,
            )
        }
    }
    if (occupants.size < 2) {
        return IntentResult.Rejected("need at least 2 players to start")
    }
    // Play at the host-chosen stakes (buy-in → starting stack + derived blinds),
    // not the engine default.
    val result = gameSessions.startHand(code, occupants, room.settings)
    if (result is IntentResult.Accepted) {
        rooms.markPlaying(code)
    }
    return result
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
 * Grace for a *forming* public/open table (Lobby, hand not yet dealt). Much
 * shorter than [DEFAULT_REAPER_GRACE]: long enough to ride out the
 * find → socket-open handshake and a brief blip, short enough that abandoning
 * the Searching screen frees the seat fast so it never lingers as a ghost in
 * another searcher's "found N players". Once the hand deals (Playing) the full
 * window applies again — mid-hand reconnect is sacred.
 */
val FORMING_PUBLIC_REAPER_GRACE: Duration = 25.seconds

/**
 * The grace to apply for a member who just dropped from [room]. A forming
 * public/open table (matchmaking-eligible, still in Lobby) gets the short
 * [FORMING_PUBLIC_REAPER_GRACE]; everything else — a live hand, a private room —
 * gets [default]. Extracted so the policy is unit-testable without standing up
 * a socket.
 */
internal fun effectiveReaperGrace(room: Room?, default: Duration): Duration {
    val forming = room != null && room.isMatchmakingEligible && room.status == RoomStatus.Lobby
    return if (forming) FORMING_PUBLIC_REAPER_GRACE else default
}

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
