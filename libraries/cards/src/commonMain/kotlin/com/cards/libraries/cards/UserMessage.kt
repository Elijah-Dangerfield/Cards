package com.dangerfield.cards.libraries.cards

/**
 * One in-app dialog the server has scheduled for the current user.
 *
 * The client renders these as a [Dialog] (`:libraries:ui`) on the next
 * foreground / cold-boot. [emoji] populates the dialog's emoji bubble
 * (omit for no bubble); [deepLink] makes the CTA button a navigate-and-ack
 * (omit for a plain dismiss).
 *
 * Authored on the server via the admin endpoints — see
 * `apps/server/.../routes/AdminRoutes.kt` and the `admin-grant-chips` /
 * `admin-send-message` GitHub Actions workflows.
 */
data class UserMessage(
    val id: String,
    val emoji: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochMs: Long,
)
