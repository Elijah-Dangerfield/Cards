package com.dangerfield.cards.libraries.networking

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default [AuthTokenProvider] binding for apps that don't ship auth yet.
 * Returns `null` for every call so unauthenticated apps work out of the
 * box.
 *
 * **Why this lives in the api module instead of `:impl`:** anvil's
 * `replaces` argument requires the replaced class to be reference-able
 * from the replacing module. With the strict
 * "only `:apps:*` may depend on `*:impl`" rule, an auth feature's impl
 * module can't see anything in `:libraries:networking:impl`. Keeping the
 * default binding here lets feature impls write
 *  `@ContributesBinding(AppScope::class, replaces = [NoOpAuthTokenProvider::class])`
 * to take over cleanly.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpAuthTokenProvider : AuthTokenProvider {
    override suspend fun getAccessToken(): String? = null
}
