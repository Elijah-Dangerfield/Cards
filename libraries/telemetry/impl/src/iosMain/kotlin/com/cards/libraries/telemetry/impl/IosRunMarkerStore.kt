package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSUserDefaults
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * `NSUserDefaults` is the only store on iOS that is both trivially available
 * at lifecycle-callback time and flushed without us scheduling anything —
 * which is the whole requirement, since the run this marker describes is one
 * that gets killed without a callback. [NSUserDefaults.synchronize] is
 * redundant on modern iOS for a normally-exiting process and is kept anyway:
 * a watchdog kill seconds after a background transition is exactly the case
 * where "the system will get to it" is the assumption under test.
 *
 * Writes happen a couple of times per session, so forcing the flush costs
 * nothing worth measuring.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosRunMarkerStore : RunMarkerStore {

    override fun read(): String? =
        Catching { NSUserDefaults.standardUserDefaults.stringForKey(MARKER_KEY) }.getOrNull()

    override fun write(value: String?) {
        Catching {
            val defaults = NSUserDefaults.standardUserDefaults
            when (value) {
                null -> defaults.removeObjectForKey(MARKER_KEY)
                else -> defaults.setObject(value, forKey = MARKER_KEY)
            }
            defaults.synchronize()
        }
    }

    private companion object {
        const val MARKER_KEY = "telemetry.runMarker"
    }
}
