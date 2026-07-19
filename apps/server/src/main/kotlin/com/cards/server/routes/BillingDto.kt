package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * `POST /v1/billing/redeem` request. [store] is the platform marketplace
 * (`"apple"` | `"google"`); [token] is the opaque platform proof (a
 * StoreKit 2 signed-transaction JWS, or a Play purchase token). The userId
 * is never in the body — it comes from the JWT `sub` claim, and the
 * receipt must carry a matching account token.
 *
 * [replayed] is true only when the client is draining a store-replayed,
 * unfinished transaction (the outstanding-purchase path), false for an
 * interactive buy. It gates grant-on-replay: the account-binding relaxation
 * that recovers a paid-but-wrong-account receipt is only ever considered for a
 * StoreKit-replayed transaction, never a receipt the client submits directly in
 * the normal buy flow (`docs/wiki/purchases.md`). Defaults false so an older
 * client is treated as a strict interactive buy.
 */
@Serializable
data class RedeemRequest(
    val store: String,
    val productId: String,
    val token: String,
    val replayed: Boolean = false,
)

/**
 * `POST /v1/billing/redeem` success response. [balance] is the
 * authoritative post-grant chip balance the client reflects directly.
 * [alreadyRedeemed] is true on an idempotent replay (the transaction was
 * already redeemed; the balance is unchanged by this call) so the client
 * can suppress a duplicate "chips added" celebration.
 */
@Serializable
data class RedeemResponse(
    val balance: Long,
    val grantedChips: Long,
    val alreadyRedeemed: Boolean,
)
