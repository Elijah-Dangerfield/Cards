package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.domain.CashOutResult
import com.dangerfield.cards.server.domain.TableSessionService
import com.dangerfield.cards.server.domain.UserId
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("LeaveSettlement")

/**
 * Settle a leaving player's table stack back to their wallet, respecting the
 * all-in-live deferral. This is the single settlement decision shared by the two
 * paths a member can leave a room by:
 *
 *  - the synchronous REST `DELETE /v1/rooms/{code}/me` (so the leave call itself
 *    can return the authoritative post-cash-out balance — MP-29), and
 *  - the socket's `MemberLeft` handler (an involuntary reap, or a duplicate of the
 *    REST leave arriving over the wire).
 *
 * Both call here so the "defer if all-in in a live hand, else cash out at
 * stackFor" rule can't drift between them. Every branch is keyed/idempotent under
 * [TableSessionService.cashOut] (credit lands at most once; a second call once the
 * session row is closed is a [CashOutResult.NoActiveSession]), so running it from
 * the REST path and again per connected socket is harmless.
 *
 * Returns the [CashOutResult] when a cash-out was attempted now, or `null` when
 * settlement was **deferred** — the leaver was all-in in a live hand, so their
 * committed chips are still live and [GameSession.departedSettlements] pays their
 * resolved stack when that hand completes. A null return means "no final balance
 * is knowable yet"; the caller must not report a settled balance.
 */
suspend fun settleLeaver(
    code: String,
    userId: UserId,
    gameSessions: GameSessionRegistry,
    tableSessions: TableSessionService,
): CashOutResult? {
    val session = gameSessions.peek(code)
    // An all-in leaver keeps their showdown right (the engine never folds an
    // all-in seat), so their committed chips are still live. Decided atomically
    // against the hand: if they're all-in in a live hand, defer — cashing out
    // their stackFor of 0 now would burn a pot they go on to win (MP-17);
    // departedSettlements pays their resolved stack when the hand completes.
    val deferred = session?.deferSettlementIfAllInLive(userId.value.toString()) ?: false
    if (deferred) return null

    // Live seat stack if they're still seated; else the stack they settled with
    // last hand (0 for a busted-and-dropped player) — never a full-escrow refund,
    // which would mint their lost stake (MP-13). Null only when never dealt in,
    // where cashOut refunds the funded amount.
    val stack = session?.state?.value?.stackFor(userId)
        ?: session?.lastKnownStack(userId.value.toString())
    return Catching { tableSessions.cashOut(userId, stack) }
        .onFailure { e -> log.warn("cashOut failed for room=$code user=${userId.value}", e) }
        .getOrNull()
}
