package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthUnready

/**
 * The repo-level binary for an [AuthUnready] failure: true when the user
 * genuinely lacks a usable account (surface "sign in"); false for transient
 * states that must read as a connection problem, never as "account needed"
 * (an onboarded guest reads that as lost progress).
 */
internal val AuthUnready.isAccountProblem: Boolean
    get() = when (reason) {
        AuthReason.NeedAccount,
        AuthReason.NeedClaimedAccount,
        AuthReason.SessionExpired,
        -> true

        AuthReason.Offline,
        AuthReason.FinishingSetup,
        -> false
    }
