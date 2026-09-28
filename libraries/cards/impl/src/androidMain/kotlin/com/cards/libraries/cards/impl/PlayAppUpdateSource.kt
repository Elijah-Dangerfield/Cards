package com.dangerfield.cards.libraries.cards.impl

import android.content.Context
import com.dangerfield.cards.libraries.cards.AppUpdateSource
import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.tryWithTimeout
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private val PlayCheckTimeout = 5.seconds

/**
 * Play In-App Updates for availability, [PublishedReleaseVersion] for the name.
 *
 * Play answers per install, so a sideload, a device without Play, or a build
 * Play is still processing all come back as "no update" (the Task fails with
 * `ERROR_APP_NOT_OWNED` and friends, which [awaitOrNull] folds into null). The
 * timeout is belt and braces: the check is already off the Home critical path,
 * but a Task that never settles shouldn't hold a coroutine for the process
 * lifetime either.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class PlayAppUpdateSource(
    private val context: Context,
    private val publishedRelease: PublishedReleaseVersion,
) : AppUpdateSource {

    private val logger = KLog.withTag("PlayAppUpdateSource")

    override suspend fun latestAvailableVersion(): AppVersion? =
        tryWithTimeout(PlayCheckTimeout) {
            Catching {
                val info: AppUpdateInfo = AppUpdateManagerFactory.create(context).appUpdateInfo.awaitOrNull()
                    ?: return@Catching null
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@Catching null
                publishedRelease.nameFor(info.availableVersionCode())
            }
        }
            .onFailure { logger.d(it) { "Play update check failed; not prompting." } }
            .getOrNull()
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}
