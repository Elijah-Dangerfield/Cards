package com.dangerfield.cards.libraries.identity.auth

/**
 * Classifies a just-completed authentication into a typed [AuthOutcome] so
 * onboarding / verify / claim branch on `SignedUp` / `SignedIn` / `Linked`
 * instead of each rebuilding new-vs-returning from a raw boolean.
 */
interface AuthOutcomeClassifier {

    /**
     * [wasLink] is what the caller statically knows: it invoked a link op on an
     * anonymous guest, so the result is [AuthOutcome.Linked]. Otherwise the
     * server-authoritative new-account signal decides — [AuthOutcome.SignedUp]
     * for a net-new account, [AuthOutcome.SignedIn] for a returning one (also
     * the fallback on error, so a real returning user is never trapped in
     * onboarding).
     */
    suspend fun classify(wasLink: Boolean = false): AuthOutcome
}
