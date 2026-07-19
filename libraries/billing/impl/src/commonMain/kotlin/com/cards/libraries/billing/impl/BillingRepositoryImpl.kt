package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.BillingRepository
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.apiErrorCode
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * HTTP impl of [BillingRepository]. Single endpoint:
 * `POST /v1/billing/redeem` ({ store, productId, token }) -> validate -> grant ->
 * `{ balance, grantedChips, alreadyRedeemed }`.
 *
 * Idempotent retry: the server's redeem is first-grant-wins on the store
 * transaction id, so a retried POST returns the same authoritative balance.
 * Marked `RetryPolicy.idempotent()` so a transient network blip doesn't strand a
 * paid-for purchase.
 *
 * The server's disposition rides the problem code: `receipt_dead` (400) ->
 * [RedeemOutcome.Dead] (caller finishes the stuck transaction),
 * `receipt_account_mismatch` (409) -> [RedeemOutcome.Mismatch] (recoverable via
 * sign-in-to-claim / grant-on-replay), `receipt_claim_sign_in` (409) ->
 * [RedeemOutcome.ClaimSignIn] (an anonymous caller must sign in to claim; leave
 * the transaction unfinished). Everything else — `receipt_transient` (503), any
 * other 4xx (unknown product / catalog drift), a 5xx, an unreachable server, or
 * a non-real-store transaction ([BillingPlatform.Fake]) — maps to
 * [RedeemOutcome.Transient] (no credit, left unfinished for a later retry).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BillingRepositoryImpl(
    private val networkClient: NetworkClient,
) : BillingRepository {

    private val logger = KLog.withTag("BillingRepository")

    override suspend fun redeem(
        catalogProductId: String,
        transaction: PurchaseTransaction,
        replayed: Boolean,
    ): RedeemOutcome {
        val store = transaction.platform.wireStore() ?: run {
            logger.w { "redeem skipped: ${transaction.platform} has no server store mapping" }
            return RedeemOutcome.Transient
        }

        val result: Catching<RedeemOutcome> = networkClient.authedCall(
            description = "billing.redeem",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            val response: HttpResponse = client.post("/v1/billing/redeem") {
                contentType(ContentType.Application.Json)
                setBody(
                    RedeemRequestDto(
                        store = store,
                        productId = catalogProductId,
                        token = transaction.purchaseToken,
                        replayed = replayed,
                    ),
                )
            }
            val body: RedeemResponseDto = response.body()
            RedeemOutcome.Granted(
                balance = body.balance,
                grantedChips = body.grantedChips,
                alreadyRedeemed = body.alreadyRedeemed,
            )
        }
        return result.getOrElse { throwable -> throwable.toRedeemOutcome() }
    }

    private suspend fun Throwable.toRedeemOutcome(): RedeemOutcome =
        if (this is ClientRequestException) {
            // The client runs with expectSuccess, so a 4xx throws here before
            // the success body parses. The server's disposition rides the
            // problem code: `receipt_dead` finishes the stuck transaction
            // (BILL-13); `receipt_account_mismatch` is left recoverable; any
            // other 4xx (unknown_product, malformed body, catalog drift) is a
            // transient left unfinished for a later retry. A 5xx / 503 / timeout
            // / unreachable server is likewise transient so the launch-time
            // redeemer can recover the paid-for purchase later.
            val code = apiErrorCode()
            logger.w(this) { "redeem rejected: ${response.status} ($code)" }
            when (code) {
                RECEIPT_DEAD_CODE -> RedeemOutcome.Dead
                RECEIPT_ACCOUNT_MISMATCH_CODE -> RedeemOutcome.Mismatch
                RECEIPT_CLAIM_SIGN_IN_CODE -> RedeemOutcome.ClaimSignIn
                else -> RedeemOutcome.Transient
            }
        } else {
            logger.w(this) { "redeem unavailable (${this::class.simpleName})" }
            RedeemOutcome.Transient
        }

    private companion object {
        const val RECEIPT_DEAD_CODE = "receipt_dead"
        const val RECEIPT_ACCOUNT_MISMATCH_CODE = "receipt_account_mismatch"
        const val RECEIPT_CLAIM_SIGN_IN_CODE = "receipt_claim_sign_in"
    }
}

private fun BillingPlatform.wireStore(): String? = when (this) {
    BillingPlatform.Apple -> "apple"
    BillingPlatform.Google -> "google"
    BillingPlatform.Fake -> null
}

@Serializable
private data class RedeemRequestDto(
    val store: String,
    val productId: String,
    val token: String,
    val replayed: Boolean,
)

@Serializable
private data class RedeemResponseDto(
    val balance: Long,
    val grantedChips: Long,
    val alreadyRedeemed: Boolean,
)
