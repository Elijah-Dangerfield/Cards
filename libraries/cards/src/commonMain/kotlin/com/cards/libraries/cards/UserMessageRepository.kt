package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.StateFlow

/**
 * Local cache + read surface for server-scheduled in-app dialogs.
 *
 * The hot path is `unread` — the UI subtree that hosts the dialog
 * observes this StateFlow and pops a [Dialog] whenever the head is
 * non-null. After the user dismisses, the UI calls [ack] which removes
 * the message from the StateFlow and fires the server ack.
 *
 * Fetching is a separate concern — see [UserMessageSyncService]. The
 * repo is the cached state; the sync service is what pulls fresh
 * messages on foreground / cold-boot.
 *
 * V1 keeps the cache in memory. Messages are short-lived (acked once
 * shown), the server is the source of truth, and the next online
 * foreground re-syncs. If we ever want "show the dialog even offline
 * on cold boot," promote this to a Room cache.
 */
interface UserMessageRepository {
    val unread: StateFlow<List<UserMessage>>

    /** Replace the cached set — called by [UserMessageSyncService] after fetch. */
    suspend fun setUnread(messages: List<UserMessage>)

    /**
     * Mark [id] acked locally + on the server. The local removal happens
     * first (so the dialog dismisses immediately even if the network
     * call is slow), then the server is told. The server ack is
     * idempotent so a flaky network at this point is harmless — the
     * next sync's `unread` list won't include this id either way.
     */
    suspend fun ack(id: String)
}
