package com.dangerfield.cards.benchmark

import android.content.Intent
import com.dangerfield.cards.AndroidAppComponent
import com.dangerfield.cards.libraries.core.AppEnvironment
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog

/**
 * Lets the Baseline Profile generator put the app into a known state without
 * driving forty taps of onboarding.
 *
 * ## Why this exists
 *
 * The generator is black-box: it drives the installed binary through
 * UiAutomator, so it cannot reach in and set up state the way a test can. Left
 * to itself it has to tap through real onboarding, which is slow, breaks
 * whenever the copy changes, and mints a real account every run.
 *
 * These hooks are the standard answer: a benchmark-only entry point that skips
 * the parts that are setup rather than subject.
 *
 * ## Why it cannot be abused in a shipped build
 *
 * **Gated on the backend environment, not on a debug flag.** Profile generation
 * runs a *release* variant, so `BuildConfig.DEBUG` is false there and gating on
 * it would disable the hooks exactly where they are needed. Gating on
 * [AppEnvironment.Dev] is stronger anyway: a store build is always pointed at
 * prod, so these hooks are inert in it no matter what intent anyone sends. The
 * generator is required to pass `-Pcards.targetEnv=dev`, which is the same
 * switch, so the two constraints are one.
 *
 * ## Credentials
 *
 * A reserved account's email and password arrive as intent extras at run time,
 * supplied by the Gradle invocation from `local.properties` or a CI secret.
 * They are deliberately not defaulted, not committed, and not logged — this
 * repository is public.
 *
 * Without credentials the hooks still skip onboarding and let the app create a
 * guest account, which is enough for a profile and needs no secrets at all.
 *
 * ## Usage
 *
 * ```
 * am start -n com.dangerfield.cards/.MainActivity \
 *   --ez cards.benchmark.skipOnboarding true \
 *   --es cards.benchmark.email "<addr>" --es cards.benchmark.password "<pw>"
 * ```
 */
object BenchmarkHooks {

    const val EXTRA_SKIP_ONBOARDING = "cards.benchmark.skipOnboarding"
    const val EXTRA_EMAIL = "cards.benchmark.email"
    const val EXTRA_PASSWORD = "cards.benchmark.password"

    /** True when [intent] asks for anything and this build is allowed to obey. */
    fun isRequested(intent: Intent?): Boolean =
        AppEnvironment.current == AppEnvironment.Dev &&
            intent?.getBooleanExtra(EXTRA_SKIP_ONBOARDING, false) == true

    /**
     * Signs in if credentials were supplied, then marks onboarding complete so
     * the app opens on Home.
     *
     * Suspending and awaited by the caller on purpose: `AppViewModel` reads
     * `hasUserOnboarded` once, in its `init`, to pick the start destination. A
     * write that lands after that read is a write that does nothing, and the
     * generator would silently walk onboarding anyway and produce a profile
     * that looked fine.
     */
    suspend fun apply(intent: Intent, component: AndroidAppComponent) {
        val email = intent.getStringExtra(EXTRA_EMAIL)
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
            // Catching, not try/catch: house rule, and it keeps a cancelled
            // coroutine from being swallowed as a sign-in failure.
            Catching { component.authRepository.signInWithEmail(email, password) }
                .onFailure {
                    // Never fatal. A profile captured as a guest is worth more
                    // than no profile, and the credentials may simply be absent
                    // on a contributor's machine.
                    KLog.d { "Benchmark sign-in failed; continuing as guest" }
                }
        }

        component.appCache.update { it.copy(hasUserOnboarded = true) }
        KLog.d { "Benchmark hooks applied: starting on Home" }
    }
}
