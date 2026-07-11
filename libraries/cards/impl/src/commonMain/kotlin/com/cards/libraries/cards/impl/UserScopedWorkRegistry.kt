package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserScopedWorkStopper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Tracks the jobs running user-scoped background work (the per-syncer
 * `runWhen` cycles, the in-app-message pass) so a user switch can cancel and
 * *await* them before the departing user's stores are wiped — closing the
 * window where an in-flight sync's write lands mid-clear.
 *
 * [tracked] registers the calling coroutine's job under the user and runs the
 * block; `runWhen` invokes work directly in its per-key cycle scope, so the
 * registered job covers the whole cycle — pending retry backoff and refire
 * edges included, not just the current attempt.
 *
 * Known residual: a cycle whose first attempt hasn't dispatched yet has no
 * job to register, so a switch landing in that dispatch-tick-wide gap isn't
 * covered. Closing it needs the clear to be visible in-stream to the sync
 * loops; the schemes tried all risked a "user never syncs again" failure
 * mode, which is worse than the microsecond window.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserScopedWorkStopper::class, multibinding = true)
@Inject
class UserScopedWorkRegistry : UserScopedWorkStopper {

    private val lock = Mutex()
    private val jobsByUser = mutableMapOf<String, MutableSet<Job>>()

    suspend fun <T> tracked(userId: String, block: suspend () -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job]) {
            "tracked requires a Job in the calling context"
        }
        lock.withLock {
            val jobs = jobsByUser.getOrPut(userId) { mutableSetOf() }
            jobs.removeAll { it.isCompleted }
            jobs.add(job)
        }
        return block()
    }

    override suspend fun stopWorkFor(previousUserId: String) {
        val jobs = lock.withLock { jobsByUser.remove(previousUserId).orEmpty() }
        jobs.forEach {
            it.cancel(CancellationException("user-scoped work stopped: $previousUserId is departing"))
        }
        jobs.joinAll()
    }
}
