package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.billing.PurchaseHistoryItem
import com.dangerfield.cards.libraries.billing.PurchaseHistoryOutcome
import com.dangerfield.cards.libraries.billing.PurchaseHistoryRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import me.tatarka.inject.annotations.Inject

/**
 * Backs the purchase-history screen. [PurchaseHistoryAction.Load] fetches the
 * list; [PurchaseHistoryAction.Sync] re-runs the outstanding-purchase drain
 * (the consumables equivalent of "restore purchases") and then reloads, so a
 * stuck purchase that resolves shows up right away.
 */
class PurchaseHistoryViewModel @Inject constructor(
    private val historyRepository: PurchaseHistoryRepository,
    private val purchaseChipPack: PurchaseChipPackUseCase,
) : SEAViewModel<PurchaseHistoryState, Nothing, PurchaseHistoryAction>(
    initialStateArg = PurchaseHistoryState(),
) {

    private val logger = KLog.withTag("PurchaseHistoryViewModel")

    override suspend fun handleAction(action: PurchaseHistoryAction) {
        when (action) {
            PurchaseHistoryAction.Load -> {
                action.updateState { it.copy(loading = true) }
                load(action)
            }
            PurchaseHistoryAction.Sync -> {
                action.updateState { it.copy(syncing = true) }
                // The drain must reach the server even if the user leaves the
                // screen, but a reload only matters while they're watching — so
                // this stays on viewModelScope and simply skips the reload if the
                // VM died mid-sync.
                Catching { purchaseChipPack.redeemOutstanding() }
                    .getOrElse { logger.w(it) { "sync drain failed" } }
                load(action)
                action.updateState { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun load(action: PurchaseHistoryAction) {
        when (val outcome = historyRepository.history()) {
            is PurchaseHistoryOutcome.Loaded -> action.updateState {
                it.copy(loading = false, unavailable = false, items = outcome.items)
            }
            PurchaseHistoryOutcome.Unavailable -> action.updateState {
                it.copy(loading = false, unavailable = it.items.isEmpty())
            }
        }
    }
}

data class PurchaseHistoryState(
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val unavailable: Boolean = false,
    val items: List<PurchaseHistoryItem> = emptyList(),
)

sealed interface PurchaseHistoryAction {
    data object Load : PurchaseHistoryAction
    data object Sync : PurchaseHistoryAction
}
