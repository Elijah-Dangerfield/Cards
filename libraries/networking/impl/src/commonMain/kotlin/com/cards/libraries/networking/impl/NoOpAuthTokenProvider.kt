package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default no-op binding so `:libraries:networking:impl` is self-contained for
 * apps without auth. When you add real auth, bind your own implementation with
 * `@ContributesBinding(AppScope::class, replaces = [NoOpAuthTokenProvider::class])`
 * and it'll take over.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpAuthTokenProvider : AuthTokenProvider {
    override suspend fun getAccessToken(): String? = null
}
