package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.identity.IdentityConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default (dev) Supabase config. Hardcoded because the anon key is a
 * public client constant by design — see [IdentityConfig] kdoc.
 *
 * When prod ships, bind a separate `ProdIdentityConfig` selected by build
 * variant (Android product flavors, iOS configurations). Use
 * `@ContributesBinding(replaces = [DefaultIdentityConfig::class])` on the
 * prod binding.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultIdentityConfig : IdentityConfig {
    override val supabaseUrl: String = "https://yuqrfhdoejonclgbixlw.supabase.co"

    // Project's anon (public) API key. Public by design — safe to ship
    // in client builds (gated by Supabase RLS, not by secrecy).
    override val supabaseAnonKey: String =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIs" +
            "InJlZiI6Inl1cXJmaGRvZWpvbmNsZ2JpeGx3Iiwicm9sZSI6ImFub24iLCJ" +
            "pYXQiOjE3NzkxMDY3NDEsImV4cCI6MjA5NDY4Mjc0MX0.0xX2-uFVNic_D" +
            "36BMooHA5m8DEIOns1Y_XCVqECCtwA"
}
