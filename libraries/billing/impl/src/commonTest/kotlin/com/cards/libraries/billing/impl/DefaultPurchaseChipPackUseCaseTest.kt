package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.BillingRepository
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.billing.RealPurchasesEnabled
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.billing.StoreKitCoordinator
import com.dangerfield.cards.libraries.billing.StoreKitProduct
import com.dangerfield.cards.libraries.billing.StoreKitPurchaseResult
import com.dangerfield.cards.libraries.billing.StoreKitPurchaseStatus
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.config.AppConfigMap
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
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the [DefaultPurchaseChipPackUseCase] contract. Two credit paths gated by
 * [RealPurchasesEnabled] (BILL-5):
 *
 *  - **off** — store confirm credits chips locally + consumes;
 *    already-owned re-credits; cancel/failure/not-connected don't credit. The
 *    server redeem endpoint is never called.
 *  - **on (default)** — store confirm POSTs the receipt to redeem and reflects the
 *    server-returned authoritative balance; a rejected receipt or unreachable
 *    server grants nothing locally and surfaces a failure.
 *
 * Plus the anonymous / no-session gates short-circuit before billing in both.
 */
class DefaultPurchaseChipPackUseCaseTest : CoroutineTest() {

    // ---------- real purchases OFF (local credit) ----------

    @Test
    fun off_success_creditsChipsLocally_acknowledges_andReturnsSuccess() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val chips = FakeChipsRepository(initial = 0)
        val redeem = RecordingBillingRepository()
        val useCase = build(billing = billing, chips = chips, redeem = redeem)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.Success>(outcome)
        assertEquals(PACK.grantsChips, outcome.grantedChips)
        assertEquals(PACK.grantsChips, chips.balanceValue, "chips credited locally")
        assertEquals(1, billing.consumeCalls, "a consumable chip pack is consumed, not just acknowledged")
        assertEquals(0, billing.acknowledgeCalls, "consumables route through consume(), never acknowledge()")
        assertEquals(0, redeem.redeemCalls, "server redeem is not called when real purchases are off")
    }

    @Test
    fun off_alreadyOwned_reCreditsChips_andReturnsAlreadyOwned() = runUnitTest {
        val chips = FakeChipsRepository(initial = 0)
        val useCase = build(billing = FakeBillingClient(PurchaseResult.AlreadyOwned(TX)), chips = chips)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.AlreadyOwned>(outcome)
        assertEquals(PACK.grantsChips, chips.balanceValue, "idempotent re-credit recovers a lost purchase")
    }

    @Test
    fun off_cancelled_doesNotCredit_andReturnsCancelled() = runUnitTest {
        val chips = FakeChipsRepository(initial = 0)
        val useCase = build(billing = FakeBillingClient(PurchaseResult.UserCancelled), chips = chips)

        assertEquals(IapPurchaseOutcome.Cancelled, useCase(PACK))
        assertEquals(0L, chips.balanceValue)
    }

    @Test
    fun off_notConnected_mapsToStoreUnavailable() = runUnitTest {
        val useCase = build(billing = FakeBillingClient(PurchaseResult.NotConnected))
        assertEquals(IapPurchaseOutcome.StoreUnavailable, useCase(PACK))
    }

    @Test
    fun off_failure_mapsToFailed() = runUnitTest {
        val useCase = build(billing = FakeBillingClient(PurchaseResult.Failed("boom")))
        val outcome = useCase(PACK)
        assertIs<IapPurchaseOutcome.Failed>(outcome)
        assertEquals("boom", outcome.reason)
    }

    // ---------- real purchases ON (server-authoritative credit) ----------

    @Test
    fun on_success_redeemsServerSide_reflectsBalance_andDoesNotLocalCredit() = runUnitTest {
        val chips = FakeChipsRepository(initial = 1_000)
        val redeem = RecordingBillingRepository(
            outcome = RedeemOutcome.Granted(balance = 31_000, grantedChips = 30_000, alreadyRedeemed = false),
        )
        val billing = FakeBillingClient(PurchaseResult.Success(APPLE_TX))
        val useCase = build(billing = billing, chips = chips, redeem = redeem, realPurchases = true)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.Success>(outcome)
        assertEquals(30_000, outcome.grantedChips, "granted amount comes from the server")
        assertEquals(31_000, chips.balanceValue, "client reflects the server's authoritative balance")
        assertEquals(0, chips.addChipsCalls, "no optimistic local credit on the real path")
        assertEquals(1, redeem.redeemCalls)
        assertEquals(1, billing.consumeCalls, "the consumable is consumed after a server-authoritative grant")
        assertEquals(
            PACK.id,
            redeem.lastProductId,
            "redeem must post the catalog product id (chip_pack_medium), not the platform store " +
                "SKU (chips_medium) — the server resolves grantsChips by catalog id, so sending the " +
                "SKU 400s and the paying user gets nothing",
        )
    }

    @Test
    fun on_alreadyRedeemed_surfacesAlreadyOwned_toSuppressCelebration() = runUnitTest {
        val chips = FakeChipsRepository(initial = 31_000)
        val redeem = RecordingBillingRepository(
            outcome = RedeemOutcome.Granted(balance = 31_000, grantedChips = 30_000, alreadyRedeemed = true),
        )
        val useCase = build(
            billing = FakeBillingClient(PurchaseResult.Success(APPLE_TX)),
            chips = chips,
            redeem = redeem,
            realPurchases = true,
        )

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.AlreadyOwned>(outcome)
        assertEquals(31_000, chips.balanceValue)
    }

    @Test
    fun on_rejectedReceipt_grantsNothing_andReturnsFailed() = runUnitTest {
        val chips = FakeChipsRepository(initial = 1_000)
        val redeem = RecordingBillingRepository(outcome = RedeemOutcome.Rejected)
        val billing = FakeBillingClient(PurchaseResult.Success(APPLE_TX))
        val useCase = build(billing = billing, chips = chips, redeem = redeem, realPurchases = true)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.Failed>(outcome)
        assertEquals(1_000, chips.balanceValue, "a forged/rejected receipt mints no chips")
        assertEquals(0, chips.addChipsCalls)
        assertEquals(0, billing.consumeCalls, "nothing to consume on a rejected receipt")
    }

    @Test
    fun on_terminalRejection_finishesTransaction_soItStopsShadowingTheNextPurchase() = runUnitTest {
        // BILL-13: a terminally-rejected receipt (its appAccountToken belongs to
        // a prior, unlinkable identity) must be finished — a stuck unfinished
        // consumable is what StoreKit hands back on the next buy of the same SKU,
        // so leaving it turns one bad receipt into "every purchase fails".
        val chips = FakeChipsRepository(initial = 1_000)
        val redeem = RecordingBillingRepository(outcome = RedeemOutcome.RejectedTerminal)
        val billing = FakeBillingClient(PurchaseResult.Success(APPLE_TX))
        val useCase = build(billing = billing, chips = chips, redeem = redeem, realPurchases = true)

        val outcome = useCase(PACK)

        assertIs<IapPurchaseOutcome.Failed>(outcome)
        assertEquals(1_000, chips.balanceValue, "a terminally rejected receipt mints no chips")
        assertEquals(0, chips.addChipsCalls)
        assertEquals(1, billing.consumeCalls, "the stuck transaction is finished so it can't block the next buy")
    }

    @Test
    fun on_unreachableServer_grantsNothing_andReturnsFailed() = runUnitTest {
        val chips = FakeChipsRepository(initial = 1_000)
        val redeem = RecordingBillingRepository(outcome = RedeemOutcome.Unavailable)
        val useCase = build(
            billing = FakeBillingClient(PurchaseResult.Success(APPLE_TX)),
            chips = chips,
            redeem = redeem,
            realPurchases = true,
        )

        assertIs<IapPurchaseOutcome.Failed>(useCase(PACK))
        assertEquals(1_000, chips.balanceValue, "an unreachable redeem leaves the purchase uncredited")
    }

    // ---------- auth gates (path-independent) ----------

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

    // ---------- outstanding-purchase recovery (BILL-7) ----------

    @Test
    fun redeemOutstanding_recoversAnUncreditedPurchase() = runUnitTest {
        // The owner's TestFlight purchases 400'd at redeem and were left
        // unfinished at the store with no retry path - paid, zero chips.
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val chips = FakeChipsRepository()
        val redeem = RecordingBillingRepository(
            RedeemOutcome.Granted(balance = 42_000, grantedChips = 30_000, alreadyRedeemed = false),
        )
        val useCase = build(
            billing = billing,
            chips = chips,
            redeem = redeem,
            realPurchases = true,
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(1, redeem.redeemCalls, "the unfinished transaction is replayed through redeem")
        assertEquals("chip_pack_medium", redeem.lastProductId, "the sku maps back to the catalog product id")
        assertEquals(42_000L, chips.balanceValue, "the authoritative post-grant balance lands")
        assertEquals(1, billing.consumeCalls, "a granted replay finishes the store transaction")
    }

    @Test
    fun redeemOutstanding_leavesTheTransactionUnfinished_whenRedeemStillFails() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val chips = FakeChipsRepository(initial = 500L)
        val useCase = build(
            billing = billing,
            chips = chips,
            redeem = RecordingBillingRepository(RedeemOutcome.Unavailable),
            realPurchases = true,
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(0, billing.consumeCalls, "an unredeemed purchase must stay unfinished for the next launch")
        assertEquals(500L, chips.balanceValue, "nothing is credited locally")
    }

    @Test
    fun redeemOutstanding_terminalRejection_finishesStuckTransaction_toStopTheReplayLoop() = runUnitTest {
        // BILL-13: the tester's fresh install replays orders minted under a prior
        // identity; the server terminally rejects them. Left unfinished they
        // replay every launch and block new purchases, so the drain must finish
        // them — even though nothing is credited (the entitlement isn't
        // recovered here; that needs cross-install ownership reconciliation).
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val chips = FakeChipsRepository(initial = 500L)
        val useCase = build(
            billing = billing,
            chips = chips,
            redeem = RecordingBillingRepository(RedeemOutcome.RejectedTerminal),
            realPurchases = true,
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(1, billing.consumeCalls, "a terminally rejected replay is finished so it stops looping")
        assertEquals(500L, chips.balanceValue, "nothing is credited on a terminal rejection")
    }

    @Test
    fun redeemOutstanding_nonTerminalRejection_leavesTheTransactionUnfinished() = runUnitTest {
        val billing = FakeBillingClient(PurchaseResult.Success(TX))
        val useCase = build(
            billing = billing,
            redeem = RecordingBillingRepository(RedeemOutcome.Rejected),
            realPurchases = true,
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(0, billing.consumeCalls, "a non-terminal 4xx stays replayable for a later launch")
    }

    @Test
    fun redeemOutstanding_withoutASession_skipsUntilNextLaunch() = runUnitTest {
        val redeem = RecordingBillingRepository()
        val useCase = build(
            redeem = redeem,
            realPurchases = true,
            auth = FakeAuthRepository(AuthState.Unauthenticated()),
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(0, redeem.redeemCalls, "no session, no authed redeem - retried next launch")
    }

    @Test
    fun redeemOutstanding_isANoOp_whenRealPurchasesAreOff() = runUnitTest {
        val redeem = RecordingBillingRepository()
        val useCase = build(
            redeem = redeem,
            realPurchases = false,
            coordinator = FakeStoreKitCoordinator(unfinished = listOf(UNFINISHED)),
        )

        useCase.redeemOutstanding()

        assertEquals(0, redeem.redeemCalls)
    }

    // ---------- scaffolding ----------

    private fun build(
        billing: BillingClient = FakeBillingClient(PurchaseResult.Success(TX)),
        chips: FakeChipsRepository = FakeChipsRepository(),
        redeem: BillingRepository = RecordingBillingRepository(),
        realPurchases: Boolean = false,
        auth: AuthRepository = FakeAuthRepository(
            AuthState.Authenticated(userId = USER_ID, isAnonymous = false, email = null),
        ),
        coordinator: StoreKitCoordinator = FakeStoreKitCoordinator(),
        catalog: ProductCatalog = ProductCatalog(chipPacks = listOf(PACK)),
    ) = DefaultPurchaseChipPackUseCase(
        billingClient = billing,
        billingRepository = redeem,
        chipsRepository = chips,
        authRepository = auth,
        realPurchasesEnabled = RealPurchasesEnabled(FakeAppConfigMap(realPurchases)),
        storeKitCoordinator = coordinator,
        productsRepository = FakeProductsRepository(catalog),
    )

    private class FakeStoreKitCoordinator(
        private val unfinished: List<StoreKitPurchaseResult> = emptyList(),
    ) : StoreKitCoordinator {
        override fun loadProducts(
            productIds: List<String>,
            onComplete: (products: List<StoreKitProduct>, errorMessage: String?) -> Unit,
        ) = onComplete(emptyList(), null)

        override fun purchase(
            productId: String,
            appAccountToken: String,
            onComplete: (result: StoreKitPurchaseResult) -> Unit,
        ) = error("unused - purchases go through BillingClient in these tests")

        override fun finishTransaction(
            jwsRepresentation: String,
            onComplete: (finished: Boolean) -> Unit,
        ) = onComplete(true)

        override fun loadUnfinishedTransactions(
            onComplete: (transactions: List<StoreKitPurchaseResult>) -> Unit,
        ) = onComplete(unfinished)
    }

    private class FakeProductsRepository(private val catalog: ProductCatalog) : ProductsRepository {
        override fun observeCatalog(): Flow<ProductCatalog> = MutableStateFlow(catalog)
        override suspend fun refresh(force: Boolean): Result<ProductCatalog> = Result.success(catalog)
        override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = MutableStateFlow(null)
        override fun observeIsRefreshing(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeRefreshFailed(): Flow<Boolean> = MutableStateFlow(false)
    }

    private class FakeAppConfigMap(realPurchasesEnabled: Boolean) : AppConfigMap() {
        override val map: Map<String, *> =
            mapOf("billing" to mapOf("realPurchasesEnabled" to realPurchasesEnabled))
    }

    private class RecordingBillingRepository(
        private val outcome: RedeemOutcome =
            RedeemOutcome.Granted(balance = 0, grantedChips = 0, alreadyRedeemed = false),
    ) : BillingRepository {
        var redeemCalls = 0
            private set
        var lastProductId: String? = null
            private set
        override suspend fun redeem(catalogProductId: String, transaction: PurchaseTransaction): RedeemOutcome {
            redeemCalls += 1
            lastProductId = catalogProductId
            return outcome
        }
    }

    private class FakeBillingClient(private val result: PurchaseResult) : BillingClient {
        var purchaseCalls = 0
            private set
        var acknowledgeCalls = 0
            private set
        var consumeCalls = 0
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
        override suspend fun consume(purchaseToken: String): Boolean {
            consumeCalls += 1
            return true
        }
    }

    private class FakeChipsRepository(initial: Long? = 0L) : ChipsRepository {
        private val balance = MutableStateFlow(initial)
        val balanceValue: Long? get() = balance.value
        var addChipsCalls = 0
            private set
        override fun observeBalance(): Flow<Long?> = balance.asStateFlow()
        override suspend fun getBalance(): Long? = balance.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            addChipsCalls += 1
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
        val UNFINISHED = StoreKitPurchaseResult(
            status = StoreKitPurchaseStatus.Success,
            productId = "chips_medium",
            transactionId = "order-9",
            jwsRepresentation = "jws-9",
            purchasedAtEpochMs = 1_000L,
        )
        val TX = PurchaseTransaction(
            sku = "chips_medium",
            orderId = "order-1",
            purchaseToken = "token-1",
            platform = BillingPlatform.Fake,
            purchasedAtEpochMs = 0L,
            displayPrice = "$4.99",
        )
        val APPLE_TX = TX.copy(platform = BillingPlatform.Apple)
    }
}
