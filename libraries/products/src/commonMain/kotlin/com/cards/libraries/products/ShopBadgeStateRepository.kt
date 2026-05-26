package com.dangerfield.cards.libraries.products

import kotlinx.coroutines.flow.Flow

/**
 * Owns the "user hasn't seen these shop items yet" signal that drives
 * the bottom-nav Shop tab's badge dot.
 *
 * The rule is intentionally simple: any product id in the current
 * catalog that the user hasn't acknowledged yet counts as unseen.
 * Acknowledgement happens en masse — the moment the user lands on the
 * Shop tab, every currently-catalogued id flips to "seen", so the dot
 * clears even for items the user didn't scroll to.
 *
 * Why "en masse" instead of per-tile-impressions: the dot is a
 * notification *that the shop has changed since you last looked*, not
 * a comprehensive read-receipt. Tracking per-tile impressions would
 * mean instrumenting the grid scroll surface and storing a much larger
 * set — overkill for a single dot.
 */
interface ShopBadgeStateRepository {

    /**
     * Live signal — true when the current catalog has at least one
     * product id the user hasn't yet acknowledged via
     * [markCurrentItemsSeen]. Drives the bottom-nav Shop tab's dot.
     */
    fun observeHasUnseenItems(): Flow<Boolean>

    /**
     * Records every product id currently in the catalog as seen. Called
     * when the user opens the Shop tab (whether by tapping it or via a
     * deep-link); the dot clears on the next emission of
     * [observeHasUnseenItems]. No-op if the catalog hasn't loaded yet.
     */
    suspend fun markCurrentItemsSeen()
}
