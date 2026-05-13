package com.dangerfield.cards.libraries.config

import com.dangerfield.cards.libraries.core.Catching

/**
 * Contract for work that guarantees config data is available before critical flows run.
 */
fun interface EnsureAppConfigLoaded {
    /** Performs the load operation and returns [Catching] to surface failures to callers. */
    suspend operator fun invoke(): Catching<Unit>
}
