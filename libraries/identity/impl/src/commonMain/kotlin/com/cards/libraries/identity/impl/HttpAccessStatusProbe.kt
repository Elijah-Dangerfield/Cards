package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.AccessStatusProbe
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * `GET /v1/me` as the account-status probe. The response is discarded on
 * purpose: what matters happens on the way back, in the response validator that
 * clears the ban on a `200` and re-latches it on a `403`.
 *
 * Swallowing the failure is the contract — the caller reads
 * `BannedState.denial`, so a thrown exception here would only be noise. Offline
 * leaves the flag exactly as it was, which is right: not reaching the server is
 * not evidence either way.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpAccessStatusProbe(
    private val profileApi: ProfileApi,
) : AccessStatusProbe {

    private val logger = KLog.withTag("AccessStatusProbe")

    override suspend fun recheck() {
        Catching { profileApi.me() }
            .onFailure { logger.i(it) { "Access probe did not come back clean — leaving the flag as it is" } }
    }
}
