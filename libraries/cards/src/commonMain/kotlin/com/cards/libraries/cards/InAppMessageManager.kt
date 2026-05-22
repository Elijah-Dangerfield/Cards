package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.StateFlow

/**
 * The single display gate for server-scheduled dialogs.
 *
 * Lifecycle:
 *  - Subscribes (as an [AppEventListener]) to cold-boot + warm-foreground.
 *  - On each event: at most one [UserMessage] of kind [UserMessageKind.Dialog]
 *    is consumed from [UserMessageRepository] and exposed via [current].
 *  - The host UI ([UserMessageOverlay] in :apps:compose) renders the
 *    Dialog while [current] is non-null.
 *  - On dismiss / CTA tap the host calls [dismissCurrent] which clears
 *    [current]; the next message waits for the next foreground.
 *
 * Why this is its own class instead of folded into the repo: the
 * "1 per foreground" decision is policy, not storage. Future variants
 * ("2 per cold boot, 1 per warm resume", "no dialogs during a
 * tutorial flow") plug in here without changing the repository's
 * cache semantics. Keeps the rest of the app talking to one
 * StateFlow regardless of how the policy evolves.
 *
 * The fetch (POSTing acks + receiving fresh messages) is owned by
 * [UserMessageRepository.sync] and runs on the same lifecycle hook —
 * the manager awaits identity, fires sync, then consumes from the
 * repo's cache.
 */
interface InAppMessageManager {
    /**
     * The currently-displayed dialog, or null when nothing is showing.
     * The overlay collects this and renders the Dialog whenever it's
     * non-null.
     */
    val current: StateFlow<UserMessage?>

    /**
     * Clears the currently-displayed dialog. Called from the host UI
     * on dismiss or CTA tap. The dialog row is already marked
     * shown + pending-ack at consume time, so this is a pure UI clear.
     */
    fun dismissCurrent()
}
