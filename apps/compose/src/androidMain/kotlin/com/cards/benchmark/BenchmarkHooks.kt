package com.dangerfield.cards.benchmark

import android.content.Intent
import com.dangerfield.cards.AndroidAppComponent
import com.dangerfield.cards.libraries.core.AppEnvironment
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
     * Marks onboarding complete, blocking only for as long as a local cache
     * write takes.
     *
     * This *has* to happen before `AppViewModel`'s `init` reads
     * `hasUserOnboarded` to pick the start destination — a write that lands
     * after that read does nothing, and the generator silently walks onboarding
     * anyway and produces a profile that looks fine.
     *
     * **Nothing network-bound may go in here.** An earlier version also awaited
     * sign-in, which put a network round trip on the main thread inside
     * `onCreate`: the activity never finished launching, no frame rendered, and
     * the benchmark failed with a blank screen and "unable to confirm activity
     * launch completion". The timeout is the belt to that braces — a wedged
     * cache write should fail the benchmark, never hang the app.
     */
    fun applyStartDestination(component: AndroidAppComponent) {
        Catching {
            runBlocking {
                withTimeout(CACHE_WRITE_TIMEOUT_MS) {
                    component.appCache.update { it.copy(hasUserOnboarded = true) }
                }
            }
        }.onFailure { KLog.d { "Benchmark: could not set onboarded flag; walking onboarding" } }
    }

    /**
     * Signs in as the reserved account, off the launch path.
     *
     * Fire-and-forget on purpose. A bots table is served by `LocalBotsSession`
     * and needs no account at all, so sign-in is a convenience — it keeps the
     * generator from minting a throwaway account per run. Making launch wait on
     * it buys nothing and costs the whole benchmark.
     */
    fun signInIfCredentialsProvided(intent: Intent, component: AndroidAppComponent) {
        val email = intent.getStringExtra(EXTRA_EMAIL) ?: return
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return
        if (email.isBlank() || password.isBlank()) return

        // Its own scope rather than the app graph's: this is benchmark-only
        // work and has no business outliving or interfering with app-scoped
        // coroutines. Nothing awaits it, so it is deliberately unstructured.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Catching { component.authRepository.signInWithEmail(email, password) }
                .onFailure { KLog.d { "Benchmark sign-in failed; continuing as guest" } }
        }
    }

    /** A local cache write. Anything near this is already pathological. */
    private const val CACHE_WRITE_TIMEOUT_MS = 3_000L
}
