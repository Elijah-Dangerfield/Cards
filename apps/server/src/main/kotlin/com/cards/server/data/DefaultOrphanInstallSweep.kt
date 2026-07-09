package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.InstallSweepResult
import com.dangerfield.cards.server.domain.OrphanInstallSweep
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID

/**
 * L1 install_id-based orphan cleanup.
 *
 * Pipeline:
 *  1. [ProfileRepository.findInstallSiblings] runs the cheap SQL gate
 *     (install_id match, not the caller, anonymous, zero IAP spend).
 *  2. Per-candidate verification via the shared [OrphanCandidateVerifier]
 *     (no IAP spend, no engagement-grade inventory, at or below level 1,
 *     no active room seat) — the same never-delete-progress guards the
 *     scheduled TTL sweep applies.
 *  3. Verified candidates get deleted via the same path as DELETE
 *     /v1/me: [SupabaseAdminClient.deleteUser] (the FK CASCADE wipes
 *     the dependent rows from profiles / wallet / inventory / etc.)
 *     followed by [ProfileRepository.delete] defensively.
 *
 * Per-candidate error handling matches [DefaultOrphanAnonymousSweep] —
 * one failed delete logs + bumps the failure count, the sweep moves on,
 * the next /v1/me retries. Keeps a transient Supabase 5xx from
 * blocking the happy path; the orphan is still eligible next call.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class DefaultOrphanInstallSweep(
    private val profileRepository: ProfileRepository,
    private val adminClient: SupabaseAdminClient,
    private val verifier: OrphanCandidateVerifier,
) : OrphanInstallSweep {

    private val logger = LoggerFactory.getLogger(DefaultOrphanInstallSweep::class.java)

    override suspend fun run(currentInstallId: UUID, currentUserId: UserId): InstallSweepResult {
        val candidates = profileRepository.findInstallSiblings(currentInstallId, currentUserId)
        if (candidates.isEmpty()) {
            return InstallSweepResult(
                candidatesFound = 0,
                deleted = 0,
                skipped = 0,
                failedToDelete = 0,
                notConfigured = false,
            )
        }

        var deleted = 0
        var skipped = 0
        var failed = 0
        var notConfigured = false

        for (candidate in candidates) {
            val skipReason = verifier.skipReason(candidate)
            if (skipReason != null) {
                logger.info("L1 sweep preserved {}: {}", candidate, skipReason)
                skipped++
                continue
            }
            when (val outcome = adminClient.deleteUser(candidate)) {
                DeleteUserResult.Success, DeleteUserResult.AlreadyGone -> {
                    try {
                        profileRepository.delete(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn(
                            "L1 sweep: profile delete failed after admin delete for {}; will retry next call",
                            candidate, e,
                        )
                    }
                    deleted++
                }
                DeleteUserResult.NotConfigured -> {
                    logger.warn("L1 sweep skipped: SUPABASE_SERVICE_ROLE_KEY is not set")
                    notConfigured = true
                    break
                }
                is DeleteUserResult.Failure -> {
                    logger.warn(
                        "L1 sweep: failed to delete sibling anon user {} (status={}); will retry next call",
                        candidate, outcome.statusCode, outcome.cause,
                    )
                    failed++
                }
            }
        }

        return InstallSweepResult(
            candidatesFound = candidates.size,
            deleted = deleted,
            skipped = skipped,
            failedToDelete = failed,
            notConfigured = notConfigured,
        )
    }

}
