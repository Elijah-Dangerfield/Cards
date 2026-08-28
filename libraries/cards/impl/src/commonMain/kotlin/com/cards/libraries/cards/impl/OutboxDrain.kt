package com.dangerfield.cards.libraries.cards.impl

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

/**
 * Flushes a sync outbox one bounded page at a time until it drains.
 *
 * [flushPage] posts the page, marks whatever the server resolved, and returns
 * how many rows that was. It runs at least once even when the outbox is empty
 * — an event-less POST is the "hydrate my totals" pulse a cold boot or a
 * reinstall depends on.
 *
 * Draining stops when a page comes back short (the outbox is empty) or when
 * the server resolved nothing from a full page. That second guard matters:
 * without it, rows the server keeps refusing to resolve would be re-read and
 * re-posted forever.
 */
internal suspend fun <T> drainOutbox(
    loadPage: suspend (limit: Int) -> List<T>,
    flushPage: suspend (page: List<T>) -> Int,
    pageSize: Int = OUTBOX_PAGE_SIZE,
    maxPages: Int = OUTBOX_MAX_PAGES_PER_SYNC,
) {
    repeat(maxPages) {
        val page = loadPage(pageSize)
        val resolved = flushPage(page)
        if (page.size < pageSize || resolved == 0) return
    }
}
