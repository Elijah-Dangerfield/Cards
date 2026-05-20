package com.dangerfield.cards.server.domain

import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Where the message surfaces in the client. Authored by the admin at
 * send time.
 *
 * - [Dialog] — pops as a modal on the next foreground / cold-boot.
 *   Default. Use for moments the user should see right now ("Christmas
 *   chips!", "your refund landed").
 * - [Inbox]  — passive entry in the in-app Notifications screen +
 *   bottom-bar badge. Use for things that don't need to interrupt
 *   ("maintenance Sunday", "we fixed the bug that ate your hand").
 *
 * Stored as TEXT in Postgres with a CHECK constraint — see V9.
 */
enum class UserMessageKind {
    Dialog,
    Inbox;

    val wire: String get() = when (this) {
        Dialog -> "dialog"
        Inbox -> "inbox"
    }

    companion object {
        fun parse(s: String): UserMessageKind = when (s) {
            "dialog" -> Dialog
            "inbox" -> Inbox
            else -> error("Unknown UserMessageKind wire value: $s")
        }
    }
}

/**
 * One in-app message scheduled for a single user.
 *
 * `emoji` and `deepLink` are nullable; `expiresAt` nullable = "never
 * expires". `ackedAt` flips from null → set the first time the user
 * dismisses (dialog) or views the inbox screen with this row visible.
 * Subsequent acks are no-ops.
 */
@OptIn(ExperimentalTime::class)
data class UserMessage(
    val id: UUID,
    val userId: UserId,
    val idempotencyKey: String,
    val kind: UserMessageKind,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val ackedAt: Instant?,
)

/**
 * Result of [UserMessageRepository.create]. Mirrors the wallet's
 * [ApplyOutcome] shape: the admin can retry safely and tell whether the
 * first attempt landed (`wasAlreadyCreated = false`) or the request was
 * a replay (`wasAlreadyCreated = true`).
 */
@OptIn(ExperimentalTime::class)
data class CreateMessageOutcome(
    val message: UserMessage,
    val wasAlreadyCreated: Boolean,
)

/**
 * Result of [UserMessageRepository.sweepExpiredAndAcked]. Returned as
 * counts so the cron caller can log them.
 */
data class MessageSweepResult(
    val ackedPurged: Int,
    val expiredUnackedPurged: Int,
) {
    val total: Int get() = ackedPurged + expiredUnackedPurged
}

/**
 * Per-user in-app message storage.
 *
 * Write side is admin-only — `create(...)` is called from
 * `/v1/admin/messages` (and from the chip-grant endpoint when the
 * caller wants the grant to land with a dialog).
 *
 * Read side (`unreadFor`) returns only rows that are still unacked
 * AND not expired. The combined filter keeps clients from seeing
 * stale notices even if the sweep cron is lagging.
 *
 * Ack side: [ackMany] flips many ids in one statement — the client
 * batches dismissals between syncs.
 *
 * Idempotency: `(userId, idempotencyKey)` is the dedup boundary on
 * create. A replay returns the existing row + `wasAlreadyCreated = true`.
 */
@OptIn(ExperimentalTime::class)
interface UserMessageRepository {

    /**
     * Insert a new message for [userId], or return the existing row if
     * `(userId, idempotencyKey)` was already used. [id] is server-
     * generated; pass it explicitly to keep tests deterministic.
     */
    suspend fun create(
        id: UUID,
        userId: UserId,
        idempotencyKey: String,
        kind: UserMessageKind,
        emoji: String?,
        title: String,
        body: String,
        deepLink: String?,
        expiresAt: Instant?,
    ): CreateMessageOutcome

    /**
     * Unacked + unexpired messages for [userId], oldest first. Capped
     * at [limit] so a buggy bulk-write can't trap a user in an
     * unbounded delivery loop. [now] is passed in so tests can be
     * deterministic about the expiry boundary.
     */
    suspend fun unreadFor(
        userId: UserId,
        now: Instant,
        limit: Int = MAX_UNREAD_PER_FETCH,
    ): List<UserMessage>

    /**
     * Flip the listed ids' `acked_at` to [at] in a single statement.
     * Filters by [userId] so a client can't ack another user's
     * messages. Returns the number of rows actually flipped — already-
     * acked / unknown / wrong-user rows are skipped silently.
     */
    suspend fun ackMany(userId: UserId, ids: List<UUID>, at: Instant): Int

    /**
     * Server-side housekeeping. Deletes acked rows (always) and
     * expired-but-unacked rows older than [now]. Returns a count
     * breakdown for the cron caller to log.
     *
     * Why split the two cases:
     *  - Acked rows are storage waste; deleting any age is safe.
     *  - Expired unacked rows are user-visible state that simply
     *    won't surface anymore — deletion is cleanup, not a behavior
     *    change.
     */
    suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult

    /**
     * Wipe every message for [userId]. Called from the `DELETE /v1/me`
     * cascade so an account delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)

    companion object {
        const val MAX_UNREAD_PER_FETCH: Int = 25
    }
}
