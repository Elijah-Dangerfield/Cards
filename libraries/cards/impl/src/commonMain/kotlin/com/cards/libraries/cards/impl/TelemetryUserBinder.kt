package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Forwards every resolved [Profile.Authenticated] into the telemetry
 * layer so Sentry attributes crashes to a real user id instead of
 * "unknown user." Anonymous users count — their `id` is stable per
 * install, which is good enough to correlate pre-claim sessions.
 *
 * Eagerly started on cold boot so the user id is set before the first
 * crash window opens. Profile is hot — anything that flips us to a
 * fresh authenticated profile (post sign-in / sign-out re-bootstrap)
 * refreshes the telemetry user without further wiring.
 *
 * `email` is forwarded for claimed users (when [Profile.Authenticated]
 * carries it) and left null for anon. Display name doubles as `username`.
 *
 * The fallback ([Profile.Fallback]) is silently ignored — there's no
 * real user to attribute crashes to in that branch.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class TelemetryUserBinder(
    private val profileProvider: () -> ProfileRepository,
    private val telemetry: Telemetry,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("TelemetryUser")
    private val profile: ProfileRepository by lazy { profileProvider() }
    private var job: Job? = null

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        if (job != null) return
        job = appScope.launch {
            profile.observe()
                .filterIsInstance<Profile.Authenticated>()
                .distinctUntilChanged { a, b ->
                    a.id == b.id &&
                        a.displayName == b.displayName &&
                        a.email == b.email
                }
                .collect { resolved ->
                    telemetry.setUser(
                        email = resolved.email,
                        name = resolved.displayName,
                        id = resolved.id,
                    )
                    logger.d { "Telemetry user set to ${resolved.id} (anon=${resolved.isAnonymous})" }
                }
        }
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // Only clear on a true sign-out / delete. On an account *switch*
        // (current != null) the profile observer above re-binds telemetry to
        // the incoming user, so there's nothing to null out.
        if (event.isSignedOut) {
            telemetry.setUser(email = null, name = null, id = null)
        }
    }
}
