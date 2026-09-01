package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.logging.Logger

/**
 * How many outbox rows ride in one sync request.
 *
 * Sized for the worst case, not the common one: a steady-state flush carries
 * a handful of rows and never pages at all. 200 XP events is roughly 30 KB of
 * JSON, which a phone on a bad uplink finishes well inside the client's 30s
 * request timeout, and it keeps the server's batch insert an order of
 * magnitude under Postgres's bind-parameter ceiling. Small enough that a page
 * that fails costs almost nothing to redo; large enough that the 2,703-row
 * backlog behind ENG-45 clears in a couple of sync edges rather than
 * hundreds.
 */
internal const val OUTBOX_PAGE_SIZE = 200

/**
 * Requests one `sync()` will make before yielding. Bounds the work a single
 * trigger edge can do; whatever is left drains on the next one.
 */
internal const val OUTBOX_MAX_PAGES_PER_SYNC = 10

/** Why [drainOutbox] stopped. Anything but [Drained] left rows behind. */
internal enum class OutboxDrainOutcome {
    /** The outbox is empty. */
    Drained,

    /** Rows remain; this sync spent its page budget and the next edge continues. */
    BudgetSpent,

    /**
     * The same page came back twice, so flushing it changed nothing. Rows the
     * server won't resolve, or acks whose keys don't match anything local.
     * Without this the loop would re-post an identical page every iteration.
     */
    Stalled,
}

/**
 * Flushes a sync outbox one bounded page at a time until it drains.
 *
 * [flushPage] posts the page and marks whatever the server resolved. It runs
 * at least once even when the outbox is empty — an event-less POST is the
 * "hydrate my totals" pulse a cold boot or a reinstall depends on.
 *
 * Progress is judged on the outbox shrinking, not on the server answering:
 * a response that resolves keys the local table doesn't hold would otherwise
 * read as progress and re-post the same page until the budget ran out.
 */
internal suspend fun <T> drainOutbox(
    loadPage: suspend (limit: Int) -> List<T>,
    flushPage: suspend (page: List<T>) -> Unit,
    keyOf: (T) -> String,
    pageSize: Int = OUTBOX_PAGE_SIZE,
    maxPages: Int = OUTBOX_MAX_PAGES_PER_SYNC,
): OutboxDrainOutcome {
    var previousKeys: List<String>? = null
    repeat(maxPages) {
        val page = loadPage(pageSize)
        val keys = page.map(keyOf)
        if (keys == previousKeys) return OutboxDrainOutcome.Stalled
        previousKeys = keys

        flushPage(page)
        if (page.size < pageSize) return OutboxDrainOutcome.Drained
    }
    return OutboxDrainOutcome.BudgetSpent
}

/**
 * Says so when a sync left rows behind.
 *
 * A drain that gives up returns normally and the sync reports success, so
 * without this a player wedged behind rows the server won't take looks exactly
 * like a clean sync. That silence is how ENG-45 stayed invisible for eight
 * days.
 */
internal fun OutboxDrainOutcome.warnIfIncomplete(logger: Logger, outbox: String) {
    when (this) {
        OutboxDrainOutcome.Drained -> Unit
        OutboxDrainOutcome.BudgetSpent -> logger.w {
            "$outbox outbox still has rows after spending this sync's page budget; " +
                "the next sync edge continues."
        }
        OutboxDrainOutcome.Stalled -> logger.w {
            "$outbox outbox stalled — the same page came back unresolved, so flushing " +
                "it is changing nothing."
        }
    }
}
