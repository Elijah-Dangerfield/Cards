package com.dangerfield.cards.libraries.telemetry.impl

import android.content.Context
import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * `commit()`, not `apply()`: the marker is only worth keeping if it reaches
 * disk before the process dies, and `apply()`'s background write is the one
 * that loses the race. It runs on the lifecycle callback thread a couple of
 * times per session, which is the cost this signal is worth.
 *
 * Android has `ApplicationExitInfo` ground truth already ([AndroidPreviousExitProvider]);
 * carrying the same marker here is what lets us check the iOS reading against a
 * platform that can actually answer.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidRunMarkerStore(
    private val context: Context,
) : RunMarkerStore {

    override fun read(): String? =
        Catching { preferences().getString(MARKER_KEY, null) }.getOrNull()

    override fun write(value: String?) {
        Catching {
            val editor = preferences().edit()
            when (value) {
                null -> editor.remove(MARKER_KEY)
                else -> editor.putString(MARKER_KEY, value)
            }
            editor.commit()
        }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "telemetry_run_marker"
        const val MARKER_KEY = "runMarker"
    }
}
