package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppIntegrityFingerprint
import com.dangerfield.cards.server.domain.AppIntegrityResult
import com.dangerfield.cards.server.domain.AppIntegrityVerifier
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default binding. Every check returns [AppIntegrityResult.NotConfigured]
 * so the call sites can opt into "treat as verified by policy" without
 * special-casing tests or staging environments.
 *
 * Replace with `@ContributesBinding(ServerScope::class, replaces = [NoOpAppIntegrityVerifier::class])`
 * on the real implementation when integrity verification ships. The
 * route layer needs zero changes — it already branches on
 * [AppIntegrityResult].
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class NoOpAppIntegrityVerifier : AppIntegrityVerifier {
    override suspend fun verify(
        token: String?,
        deviceFingerprint: AppIntegrityFingerprint,
    ): AppIntegrityResult = AppIntegrityResult.NotConfigured
}
