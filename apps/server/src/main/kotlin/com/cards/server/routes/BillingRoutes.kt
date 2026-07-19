package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.data.RelaxedGrantRateLimiter
import com.dangerfield.cards.server.domain.BillingEventAction
import com.dangerfield.cards.server.domain.BillingEventAttempt
import com.dangerfield.cards.server.domain.BillingEventsRepository
import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.PurchaseEnvironment
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptClassifier
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.ReceiptValidator
import com.dangerfield.cards.server.domain.RedeemDisposition
import com.dangerfield.cards.server.domain.RedeemResult
import com.dangerfield.cards.server.domain.Store
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.WALLET_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.captureToSentry
import com.dangerfield.cards.server.plugins.isAnonymousUser
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

/**
 * Server-authoritative chip-pack redemption (BILL-1).
 *
 * `POST /v1/billing/redeem` is the trust boundary that closes the
 * "forged receipt mints chips" hole: the client used to credit chips
 * locally on store confirmation and the server never saw the receipt.
 * Now the flow is validate → grant → respond:
 *
 *  1. Validate the receipt via [ReceiptValidator] (dev impl trusts the
 *     token; BILL-2 swaps in the real Apple + Google validators). A
 *     rejected receipt returns 400 and credits nothing.
 *  2. Resolve the product id to a [Product.ChipPack] in the catalog and
 *     read its server-authoritative `grantsChips` — the client never says
 *     how many chips it bought.
 *  3. Grant through [BillingRepository.redeem], idempotent on the store
 *     transaction id, returning the authoritative balance.
 *
 * Requires a valid Supabase JWT; the userId comes from the `sub` claim and
 * is pinned into the receipt so a validator can confirm the purchase
 * belongs to the caller. Per-IP rate-limited like the wallet writes.
 */
private val logger = LoggerFactory.getLogger("BillingRoutes")

/**
 * Raised (captured, never thrown) so receipt rejections group as one Sentry
 * issue with the validator's reason + store/product distinguishing occurrences.
 */
private class ReceiptRejectedException(reason: String, store: String, productId: String) :
    RuntimeException("Receipt rejected: $reason (store=$store, product=$productId)")

fun Route.billingRoutes(
    catalog: ProductCatalogSource,
    validator: ReceiptValidator,
    billing: BillingRepository,
    profiles: ProfileRepository,
    relaxedGrantRateLimiter: RelaxedGrantRateLimiter,
    billingEvents: BillingEventsRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(WALLET_WRITE_LIMIT)) {
            post("/v1/billing/redeem") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = try {
                    call.receive<RedeemRequest>()
                } catch (_: BadRequestException) {
                    return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "invalid_body",
                        "Malformed redeem request.",
                    )
                }

                val store = Store.fromWire(body.store)
                    ?: return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "invalid_store",
                        "store must be 'apple' or 'google'.",
                    )

                val product = catalog.readById(body.productId, call.clientContext())
                if (product !is Product.ChipPack) {
                    // A real client only redeems ids it got from this catalog,
                    // so this is client/catalog drift (or probing) — log it.
                    logger.warn(
                        "Redeem for unknown product '{}' (store={}, user={})",
                        body.productId, body.store, userId.value,
                    )
                    return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "unknown_product",
                        "No chip pack with that product id.",
                    )
                }

                val expectedSku = when (store) {
                    Store.Apple -> product.store.ios.sku
                    Store.Google -> product.store.android.sku
                }
                val validation = validator.validate(
                    PurchaseReceipt(
                        store = store,
                        productId = body.productId,
                        expectedSku = expectedSku,
                        token = body.token,
                        userId = userId,
                        // A pack bought before an account upgrade carries the
                        // prior identity's appAccountToken; widen the accepted
                        // set to the caller's install lineage so it still
                        // redeems instead of stranding as apple_account_mismatch
                        // (BILL-11).
                        accountLineage = profiles.findInstallLineage(userId),
                    ),
                )
                when (validation) {
                    is ReceiptValidation.Valid -> call.grantAndRespond(
                        billing = billing,
                        billingEvents = billingEvents,
                        userId = userId,
                        store = store,
                        orderId = validation.orderId,
                        productId = body.productId,
                        grantedChips = product.grantsChips,
                        environment = validation.environment,
                        relaxedAccountBinding = false,
                        receiptOwner = null,
                    )
                    is ReceiptValidation.AccountMismatch -> call.respondToAccountMismatch(
                        billing = billing,
                        billingEvents = billingEvents,
                        rateLimiter = relaxedGrantRateLimiter,
                        mismatch = validation,
                        replayed = body.replayed,
                        anonymousCaller = call.isAnonymousUser(),
                        store = store,
                        productId = body.productId,
                        grantedChips = product.grantsChips,
                        userId = userId,
                    )
                    is ReceiptValidation.Invalid -> call.respondToInvalid(
                        validation, billingEvents, store, body.productId, userId,
                    )
                }
            }
        }
    }
}

/**
 * Grant the validated transaction and answer with the authoritative balance.
 * Idempotent on the store transaction id via [BillingRepository.redeem], so a
 * retry or a racing request returns the same balance with `alreadyRedeemed`.
 * [relaxedAccountBinding] is true only on a grant-on-replay.
 */
private suspend fun ApplicationCall.grantAndRespond(
    billing: BillingRepository,
    billingEvents: BillingEventsRepository,
    userId: UserId,
    store: Store,
    orderId: String,
    productId: String,
    grantedChips: Long,
    environment: PurchaseEnvironment,
    relaxedAccountBinding: Boolean,
    receiptOwner: UserId?,
) {
    logger.info(
        "Redeeming {} for user={} (store={}, environment={}, chips={}, relaxed={})",
        productId, userId.value, store.wire, environment.wire, grantedChips, relaxedAccountBinding,
    )
    val result = billing.redeem(
        userId = userId,
        store = store.wire,
        orderId = orderId,
        productId = productId,
        grantedChips = grantedChips,
        environment = environment,
        relaxedAccountBinding = relaxedAccountBinding,
    )
    billingEvents.recordSafely(
        BillingEventAttempt(
            store = store.wire,
            transactionId = orderId,
            callerUser = userId,
            receiptOwner = receiptOwner,
            productId = productId,
            reason = if (relaxedAccountBinding) "account_mismatch_relaxed" else null,
            action = if (relaxedAccountBinding) BillingEventAction.GrantedOnReplay else BillingEventAction.Granted,
        ),
    )
    respond(
        HttpStatusCode.OK,
        RedeemResponse(
            balance = result.balance,
            grantedChips = grantedChips,
            alreadyRedeemed = result is RedeemResult.AlreadyRedeemed,
        ),
    )
}

/**
 * Handle a receipt that verified everything except the account binding
 * (`docs/wiki/purchases.md`). Two paths, decided by whether this is a
 * StoreKit-replayed transaction:
 *
 *  - **Interactive buy** (`replayed == false`) — never relaxed. The buyer is a
 *    signed-in account, so a genuine receipt carries their id; a mismatch here
 *    is someone else's receipt. 409 `receipt_account_mismatch`; the client
 *    finishes it.
 *  - **StoreKit replay** (`replayed == true`) — the grant-on-replay case. Apple
 *    only re-presents an unfinished transaction to the Apple ID that bought it,
 *    so the "different account" is almost always the same human whose in-app id
 *    changed on reinstall. The receipt is signature-valid, paid, and unrevoked
 *    (the validator confirmed all of that before reporting the mismatch), and a
 *    grant can only ever land once per transaction id, so relaxing the binding
 *    doesn't mint free chips — it only decides which of the user's own accounts
 *    gets the one grant. This RELAXES the BILL-11 strict account binding, gated
 *    to the replay path, rate-limited, and logged with a distinct wallet reason.
 *    A rate-limit trip falls back to the strict 409 so the client still finishes
 *    the transaction and the shop is unblocked.
 *
 * Before granting blind on a replay, an **anonymous** caller is nudged to sign
 * in first (`receipt_claim_sign_in`): re-login makes the receipt match its own
 * account token cleanly (a plain [ReceiptValidation.Valid] grant, no relaxation
 * at all), which is both safer and lands the chips on the durable claimed
 * account rather than a throwaway anonymous one. The client leaves the
 * transaction unfinished so the next drain after sign-in resolves it; grant-and-
 * finish stays the fallback (via the wedged escalation) if they never do.
 */
private suspend fun ApplicationCall.respondToAccountMismatch(
    billing: BillingRepository,
    billingEvents: BillingEventsRepository,
    rateLimiter: RelaxedGrantRateLimiter,
    mismatch: ReceiptValidation.AccountMismatch,
    replayed: Boolean,
    anonymousCaller: Boolean,
    store: Store,
    productId: String,
    grantedChips: Long,
    userId: UserId,
) {
    suspend fun recordMismatch(reason: String, action: BillingEventAction) = billingEvents.recordSafely(
        BillingEventAttempt(
            store = store.wire,
            transactionId = mismatch.orderId,
            callerUser = userId,
            receiptOwner = mismatch.receiptOwner,
            productId = productId,
            reason = reason,
            action = action,
        ),
    )

    if (!replayed) {
        logger.warn(
            "Interactive receipt bound to a different account: receiptOwner={} caller={} store={} product={}",
            mismatch.receiptOwner.value, userId.value, store.wire, productId,
        )
        recordMismatch("account_mismatch_interactive", BillingEventAction.Mismatch)
        return respondProblem(
            HttpStatusCode.Conflict,
            "receipt_account_mismatch",
            "This purchase belongs to a different account.",
        )
    }
    if (anonymousCaller) {
        // Prefer the clean fix: sign in so the receipt matches its own account.
        // Don't spend a rate-limit slot — we haven't granted anything.
        logger.info(
            "Grant-on-replay deferred: anonymous caller nudged to sign in to claim receiptOwner={} (store={}, product={})",
            mismatch.receiptOwner.value, store.wire, productId,
        )
        recordMismatch("account_mismatch_anonymous", BillingEventAction.ClaimSignIn)
        return respondProblem(
            HttpStatusCode.Conflict,
            "receipt_claim_sign_in",
            "Sign in to claim your purchase.",
        )
    }
    if (!rateLimiter.tryAcquire(userId)) {
        logger.warn(
            "Grant-on-replay rate-limited: refusing to relax receiptOwner={} -> caller={} (store={}, product={})",
            mismatch.receiptOwner.value, userId.value, store.wire, productId,
        )
        recordMismatch("account_mismatch_rate_limited", BillingEventAction.Mismatch)
        return respondProblem(
            HttpStatusCode.Conflict,
            "receipt_account_mismatch",
            "This purchase belongs to a different account.",
        )
    }
    logger.info(
        "Grant-on-replay: relaxing account binding receiptOwner={} -> caller={} (store={}, product={}, order={}, env={})",
        mismatch.receiptOwner.value, userId.value, store.wire, productId, mismatch.orderId, mismatch.environment.wire,
    )
    grantAndRespond(
        billing = billing,
        billingEvents = billingEvents,
        userId = userId,
        store = store,
        orderId = mismatch.orderId,
        productId = productId,
        grantedChips = grantedChips,
        environment = mismatch.environment,
        relaxedAccountBinding = true,
        receiptOwner = mismatch.receiptOwner,
    )
}

/**
 * Turn a rejected receipt into the disposition-specific response the client
 * acts on (see `docs/wiki/purchases.md`). The disposition, not the raw reason,
 * decides both the HTTP status and what the client does next:
 *
 *  - [RedeemDisposition.Transient] -> 503 `receipt_transient`: the client leaves
 *    the transaction unfinished so the launch-time redeemer retries it, instead
 *    of finishing (and stranding) a genuinely paid purchase.
 *  - [RedeemDisposition.Mismatch] -> 409 `receipt_account_mismatch`: genuine,
 *    paid, signed, just tied to a different one of the user's accounts. Left
 *    recoverable (sign-in-to-claim / grant-on-replay); not a Sentry issue
 *    because it is an expected reinstall state, tracked on the billing-health
 *    panel rather than paged on.
 *  - [RedeemDisposition.Dead] -> 400 `receipt_dead`: forged / wrong product /
 *    revoked. Worth a Sentry issue (the reason distinguishes forgery from config
 *    drift); the client finishes the stuck transaction so it stops replaying and
 *    stops shadowing new purchases (BILL-13).
 */
private suspend fun ApplicationCall.respondToInvalid(
    validation: ReceiptValidation.Invalid,
    billingEvents: BillingEventsRepository,
    store: Store,
    productId: String,
    userId: UserId,
): Unit = when (ReceiptClassifier.classify(validation)) {
    RedeemDisposition.Transient -> {
        logger.warn(
            "Receipt validation unavailable: reason={} store={} product={} user={}",
            validation.reason, store.wire, productId, userId.value,
        )
        respondProblem(
            HttpStatusCode.ServiceUnavailable,
            "receipt_transient",
            "Receipt validation is temporarily unavailable.",
        )
    }
    RedeemDisposition.Mismatch -> {
        logger.warn(
            "Receipt bound to a different account: reason={} store={} product={} user={}",
            validation.reason, store.wire, productId, userId.value,
        )
        respondProblem(
            HttpStatusCode.Conflict,
            "receipt_account_mismatch",
            "This purchase belongs to a different account.",
        )
    }
    // Wedged is never produced by the classifier, but folding it here with Dead
    // keeps the `when` total and finishes the transaction if it ever appears.
    // Wedged is never produced by the classifier, but folding it here with Dead
    // keeps the `when` total and finishes the transaction if it ever appears.
    RedeemDisposition.Dead, RedeemDisposition.Wedged -> {
        logger.warn(
            "Receipt rejected: reason={} store={} product={} user={}",
            validation.reason, store.wire, productId, userId.value,
        )
        captureToSentry(
            ReceiptRejectedException(validation.reason, store.wire, productId),
            context = "billing_redeem",
        )
        // A dead receipt that still carries a transaction id (a refund/revoke) is
        // recordable against the purchase so support can see "this was refunded";
        // a pre-decode failure (forged, unconfigured) has no id to anchor on.
        validation.orderId?.let { orderId ->
            billingEvents.recordSafely(
                BillingEventAttempt(
                    store = store.wire,
                    transactionId = orderId,
                    callerUser = userId,
                    receiptOwner = null,
                    productId = productId,
                    reason = validation.reason,
                    action = BillingEventAction.FinishedDead,
                ),
            )
        }
        respondProblem(
            HttpStatusCode.BadRequest,
            "receipt_dead",
            "The purchase receipt could not be verified.",
        )
    }
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, mapOf("error" to mapOf("code" to code, "message" to message)))

/**
 * Write a billing-events row best-effort: audit must never block or fail a
 * grant (the money-path record is `billing_transactions`), so a write failure
 * is logged and swallowed.
 */
private suspend fun BillingEventsRepository.recordSafely(attempt: BillingEventAttempt) {
    Catching { record(attempt) }.onFailure { error ->
        logger.warn(
            "billing_events write failed for store={} txn={}: {}",
            attempt.store, attempt.transactionId, error.message,
        )
    }
}
