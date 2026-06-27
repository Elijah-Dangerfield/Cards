package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.BillingRepository
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RedeemOutcome
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
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
 * A receipt the server rejects (4xx) maps to [RedeemOutcome.Rejected] — nothing
 * was granted. A non-real-store transaction ([BillingPlatform.Fake], which the
 * real endpoint can't verify) and any unreachable-server case map to
 * [RedeemOutcome.Unavailable] without ever hitting the wire / crediting.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BillingRepositoryImpl(
    private val networkClient: NetworkClient,
) : BillingRepository {

    private val logger = KLog.withTag("BillingRepository")

    override suspend fun redeem(transaction: PurchaseTransaction): RedeemOutcome {
        val store = transaction.platform.wireStore() ?: run {
            logger.w { "redeem skipped: ${transaction.platform} has no server store mapping" }
            return RedeemOutcome.Unavailable
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
                        productId = transaction.sku,
                        token = transaction.purchaseToken,
                    ),
                )
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val body: RedeemResponseDto = response.body()
                    RedeemOutcome.Granted(
                        balance = body.balance,
                        grantedChips = body.grantedChips,
                        alreadyRedeemed = body.alreadyRedeemed,
                    )
                }
                HttpStatusCode.BadRequest -> {
                    logger.w { "redeem rejected for ${transaction.sku}: ${response.status}" }
                    RedeemOutcome.Rejected
                }
                else -> {
                    logger.w { "redeem failed for ${transaction.sku}: ${response.status}" }
                    RedeemOutcome.Unavailable
                }
            }
        }
        return result.getOrDefault(RedeemOutcome.Unavailable)
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
)

@Serializable
private data class RedeemResponseDto(
    val balance: Long,
    val grantedChips: Long,
    val alreadyRedeemed: Boolean,
)
