package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.core.AppEnvironment
import com.dangerfield.cards.libraries.identity.IdentityConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Supabase auth config for the resolved [AppEnvironment] (dev or prod, picked by
 * build type — see [AppAppEnvironment.current]). The publishable key is a public
 * client constant by design; data is gated by Supabase RLS, not by secrecy.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultIdentityConfig : IdentityConfig {
    override val supabaseUrl: String = AppEnvironment.current.supabaseUrl
    override val supabasePublishableKey: String = AppEnvironment.current.supabasePublishableKey
}
