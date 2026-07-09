package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.BillingRepository
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.PurchaseReceipt
import com.dangerfield.cards.server.domain.ReceiptValidation
import com.dangerfield.cards.server.domain.ReceiptValidator
import com.dangerfield.cards.server.domain.RedeemResult
import com.dangerfield.cards.server.domain.Store
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.WALLET_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.captureToSentry
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
                    ),
                )
                val orderId = when (validation) {
                    is ReceiptValidation.Valid -> validation.orderId
                    is ReceiptValidation.Invalid -> {
                        // The user paid the store and we refused the grant —
                        // forged receipt, or config drift (wrong bundle id /
                        // environment / SKU). Both are worth a Sentry issue,
                        // with the reason distinguishing them; the client only
                        // ever sees the generic "receipt_rejected".
                        logger.warn(
                            "Receipt rejected: reason={} store={} product={} user={}",
                            validation.reason, store.wire, body.productId, userId.value,
                        )
                        captureToSentry(
                            ReceiptRejectedException(validation.reason, store.wire, body.productId),
                            context = "billing_redeem",
                        )
                        return@post call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "receipt_rejected",
                            "The purchase receipt could not be verified.",
                        )
                    }
                }

                val result = billing.redeem(
                    userId = userId,
                    store = store.wire,
                    orderId = orderId,
                    productId = body.productId,
                    grantedChips = product.grantsChips,
                )
                call.respond(
                    HttpStatusCode.OK,
                    RedeemResponse(
                        balance = result.balance,
                        grantedChips = product.grantsChips,
                        alreadyRedeemed = result is RedeemResult.AlreadyRedeemed,
                    ),
                )
            }
        }
    }
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, mapOf("error" to mapOf("code" to code, "message" to message)))
