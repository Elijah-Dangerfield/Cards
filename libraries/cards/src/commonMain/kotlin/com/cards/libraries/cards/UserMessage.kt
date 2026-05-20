package com.dangerfield.cards.libraries.cards

/**
 * Where the message surfaces in the client.
 *
 * - [Dialog] — modal pop on the next foreground / cold-boot.
 *   Gated to one-per-foreground by the dialog manager.
 * - [Inbox]  — passive entry in the Notifications screen with a
 *   bottom-bar badge for the unread count.
 *
 * Authored on the server. The wire form is the lowercase enum name;
 * unknown values fall back to [Dialog] so a future surface doesn't
 * crash older clients.
 */
enum class UserMessageKind {
    Dialog,
    Inbox;

    companion object {
        fun fromWire(s: String?): UserMessageKind = when (s?.lowercase()) {
            "inbox" -> Inbox
            else -> Dialog
        }
    }
}

/**
 * One in-app message scheduled for the current user.
 *
 * [emoji] populates the dialog bubble / inbox accent (omit for no
 * bubble). [deepLink] turns the dialog CTA / inbox row tap into a
 * navigate-and-ack (omit for plain dismiss / no-op). [expiresAtEpochMs]
 * is the absolute UTC cutoff after which the client filters this
 * message out — null = never expires.
 *
 * Authored on the server via the admin endpoints (`/v1/admin/messages`
 * + `/v1/admin/grant-chips`) and the `admin-send-message` /
 * `admin-grant-chips` GitHub Actions workflows.
 */
data class UserMessage(
    val id: String,
    val kind: UserMessageKind,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)
