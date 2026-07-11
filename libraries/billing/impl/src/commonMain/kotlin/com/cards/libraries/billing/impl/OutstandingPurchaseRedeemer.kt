package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Launch-time recovery for paid-but-uncredited purchases (BILL-7). A redeem
 * that fails (server down, receipt validation misconfigured) deliberately
 * leaves the StoreKit transaction unfinished — Apple keeps replaying it — and
 * this driver drains those through [PurchaseChipPackUseCase.redeemOutstanding]
 * once a session resolves, so the user's chips arrive on the next launch
 * instead of never. Android's coordinator reports no unfinished transactions,
 * so this is a no-op there (Play's own replay rides `AlreadyOwned`).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class OutstandingPurchaseRedeemer(
    private val purchaseChipPackUseCase: PurchaseChipPackUseCase,
    private val authRepository: AuthRepository,
    private val appScope: AppCoroutineScope,
) : AutoInit {

    init {
        appScope.launch {
            Catching {
                authRepository.awaitAuthenticated()
                purchaseChipPackUseCase.redeemOutstanding()
            }.logOnFailure { "Outstanding-purchase redeem failed; will retry next launch" }
        }
    }
}
