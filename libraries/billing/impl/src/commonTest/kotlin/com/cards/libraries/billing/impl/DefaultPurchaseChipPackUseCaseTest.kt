package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the [DefaultPurchaseChipPackUseCase] contract — the billing round-trip
 * extracted out of ShopViewModel so the shop grid and the in-game quick-buy
 * sheet share it. Covers: success credits chips + acknowledges, already-owned
 * re-credits, cancel/failure/not-connected map to outcomes without crediting,
 * and the anonymous / no-session gates short-circuit before billing.
 */
class DefaultPurchaseChipPackUseCaseTest : CoroutineTest() {

    @Test
    fun success_creditsChips_acknowledges_andReturnsSuccess() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val chips = FakeChipsRepository(initial = 0)
        val useCase = build(billing = billing, chips = chips)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.Success>(outcome)
        assertEquals(PACK.grantsChips, outcome.grantedChips)
        assertEquals(PACK.grantsChips, chips.balanceValue, "chips credited locally")
        assertEquals(1, billing.purchaseCalls)
        assertEquals(1, billing.acknowledgeCalls, "successful purchase is acknowledged")
    }

    @Test
    fun alreadyOwned_reCreditsChips_andReturnsAlreadyOwned() = runUnitTest {
        val chips = FakeChipsRepository(initial = 0)
        val useCase = build(billing = FakeBillingClient(PurchaseResult.AlreadyOwned(TX)), chips = chips)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.AlreadyOwned>(outcome)
        assertEquals(PACK.grantsChips, chips.balanceValue, "idempotent re-credit recovers a lost purchase")
    }

    @Test
    fun cancelled_doesNotCredit_andReturnsCancelled() = runUnitTest {
        val chips = FakeChipsRepository(initial = 0)
        val useCase = build(billing = FakeBillingClient(PurchaseResult.UserCancelled), chips = chips)

        assertEquals(IapPurchaseOutcome.Cancelled, useCase(PACK))
        assertEquals(0L, chips.balanceValue)
    }

    @Test
    fun notConnected_mapsToStoreUnavailable() = runUnitTest {
        val useCase = build(billing = FakeBillingClient(PurchaseResult.NotConnected))
        assertEquals(IapPurchaseOutcome.StoreUnavailable, useCase(PACK))
    }

    @Test
    fun failure_mapsToFailed() = runUnitTest {
        val useCase = build(billing = FakeBillingClient(PurchaseResult.Failed("boom")))
        val outcome = useCase(PACK)
        assertIs<IapPurchaseOutcome.Failed>(outcome)
        assertEquals("boom", outcome.reason)
    }

    @Test
    fun anonymousUser_returnsClaimAccountRequired_withoutTouchingBilling() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val useCase = build(
            billing = billing,
            auth = FakeAuthRepository(
                AuthState.Authenticated(userId = USER_ID, isAnonymous = true, email = null),
            ),
        )

        assertEquals(IapPurchaseOutcome.ClaimAccountRequired, useCase(PACK))
        assertEquals(0, billing.purchaseCalls, "anonymous users never reach the store")
    }

    @Test
    fun noSession_returnsNotSignedIn_withoutTouchingBilling() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val useCase = build(billing = billing, auth = FakeAuthRepository(AuthState.Unauthenticated()))

        assertEquals(IapPurchaseOutcome.NotSignedIn, useCase(PACK))
        assertEquals(0, billing.purchaseCalls)
    }

    // ---------- scaffolding ----------

    private fun build(
        billing: BillingClient = FakeBillingClient(PurchaseResult.Success(TX)),
        chips: FakeChipsRepository = FakeChipsRepository(),
        auth: AuthRepository = FakeAuthRepository(
            AuthState.Authenticated(userId = USER_ID, isAnonymous = false, email = null),
        ),
    ) = DefaultPurchaseChipPackUseCase(
        billingClient = billing,
        chipsRepository = chips,
        authRepository = auth,
    )

    private class FakeBillingClient(private val result: PurchaseResult) : BillingClient {
        var purchaseCalls = 0
            private set
        var acknowledgeCalls = 0
            private set
        private val _connectionState = MutableStateFlow(ConnectionState.Connected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
        override suspend fun connect(): ConnectionState = ConnectionState.Connected
        override suspend fun queryProducts(skus: Set<String>): QueryProductsResult =
            QueryProductsResult.Success(products = emptyMap())
        override suspend fun purchase(sku: String, userId: String): PurchaseResult {
            purchaseCalls += 1
            return result
        }
        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledgeCalls += 1
            return true
        }
    }

    private class FakeChipsRepository(initial: Long? = 0L) : ChipsRepository {
        private val balance = MutableStateFlow(initial)
        val balanceValue: Long? get() = balance.value
        override val walletJustCreated: StateFlow<Boolean> = MutableStateFlow(false)
        override fun observeBalance(): Flow<Long?> = balance.asStateFlow()
        override suspend fun getBalance(): Long? = balance.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            balance.value = (balance.value ?: 0L) + amount
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
            balance.value = (balance.value ?: 0L) - amount
        }
        override suspend fun setBalance(authoritativeBalance: Long) { balance.value = authoritativeBalance }
        override suspend fun deleteAll() { balance.value = null }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class FakeAuthRepository(private val state: AuthState) : AuthRepository {
        override suspend fun current(): AuthState = state
        override fun observe(): Flow<AuthState> = MutableStateFlow(state).asStateFlow()
        override suspend fun retry(): AuthState = state
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome = error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun signOut() = error("unused")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
            error("unused")
    }

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        val PACK = Product.ChipPack(
            id = "chip_pack_medium",
            title = "Tall Stack",
            subtitle = "30,000 chips",
            iconEmoji = "💰",
            grantsChips = 30_000,
            store = StoreSku("chips_medium", "$4.99"),
        )
        val TX = PurchaseTransaction(
            sku = "chips_medium",
            orderId = "order-1",
            purchaseToken = "token-1",
            platform = BillingPlatform.Fake,
            purchasedAtEpochMs = 0L,
            displayPrice = "$4.99",
        )
    }
}
