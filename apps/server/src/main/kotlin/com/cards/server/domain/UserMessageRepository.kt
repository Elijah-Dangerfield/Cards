package com.dangerfield.cards.server.domain

import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One in-app message scheduled for a single user. Authored by an admin,
 * delivered as a dialog on the next foreground after creation,
 * acknowledged exactly once via `POST /v1/me/messages/{id}/ack`.
 *
 * `emoji` and `deepLink` are nullable: omit `emoji` and the client
 * renders without a bubble; omit `deepLink` and the dialog button is a
 * plain dismiss instead of a navigate-and-ack.
 *
 * `ackedAt` flips from null → set the first time the user dismisses or
 * taps through. Subsequent acks are no-ops (idempotent).
 */
@OptIn(ExperimentalTime::class)
data class UserMessage(
    val id: UUID,
    val userId: UserId,
    val idempotencyKey: String,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAt: Instant,
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
 * Per-user in-app message storage.
 *
 * The write side is admin-only — `create(...)` is called from
 * `/v1/admin/messages` (and from the chip-grant endpoint when the
 * caller wants the grant to land with a dialog). The read side
 * (`unreadFor`) and the ack side (`ack`) are user-facing.
 *
 * Idempotency: `(userId, idempotencyKey)` is the dedup boundary on
 * create. A replay returns the existing row + `wasAlreadyCreated = true`
 * without inserting a duplicate.
 */
@OptIn(ExperimentalTime::class)
interface UserMessageRepository {

    /**
     * Insert a new message for [userId], or return the existing row if
     * `(userId, idempotencyKey)` was already used. The message id is
     * server-generated; pass [id] to make the test deterministic.
     */
    suspend fun create(
        id: UUID,
        userId: UserId,
        idempotencyKey: String,
        emoji: String?,
        title: String,
        body: String,
        deepLink: String?,
    ): CreateMessageOutcome

    /**
     * Unacked messages for [userId], oldest first. The client renders
     * them sequentially — show the first, ack on dismiss, show the
     * next. Capped at a generous limit so a buggy bulk-write can't
     * trap a user in an infinite dialog chain.
     */
    suspend fun unreadFor(userId: UserId, limit: Int = MAX_UNREAD_PER_FETCH): List<UserMessage>

    /**
     * Mark [id] acked at [at]. Returns true if a row was flipped;
     * false if the id doesn't exist, doesn't belong to [userId], or
     * was already acked. The "already acked" case is intentionally
     * indistinguishable from "doesn't exist" — the client doesn't
     * need to know the difference and we don't leak whether a
     * specific UUID exists for another user.
     */
    suspend fun ack(userId: UserId, id: UUID, at: Instant): Boolean

    /**
     * Wipe every message for [userId]. Called from the `DELETE /v1/me`
     * cascade so an account delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)

    companion object {
        const val MAX_UNREAD_PER_FETCH: Int = 25
    }
}
