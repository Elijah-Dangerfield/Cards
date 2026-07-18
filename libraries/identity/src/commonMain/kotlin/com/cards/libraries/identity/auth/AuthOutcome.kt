package com.dangerfield.cards.libraries.identity.auth

/**
 * What an authentication *was*, resolved deterministically once a session is
 * established. Replaces the ad-hoc boolean each call site rebuilt from
 * `resolveIsNewAccount()` plus a link check.
 *
 *  - [SignedUp] — a net-new account was just created (first-ever contact).
 *  - [SignedIn] — an existing account was signed into.
 *  - [Linked]   — an anonymous guest attached an identity, keeping its progress.
 *
 * `SignedUp` vs `SignedIn` comes from the server-authoritative `/v1/me`
 * `isNewAccount` signal; `Linked` is known statically from the operation the
 * caller invoked (a link on an anonymous session). See docs/decisions.md
 * "Deterministic auth-outcome state machine (AUTH-22)".
 */
sealed interface AuthOutcome {
    data object SignedUp : AuthOutcome
    data object SignedIn : AuthOutcome
    data object Linked : AuthOutcome
}
