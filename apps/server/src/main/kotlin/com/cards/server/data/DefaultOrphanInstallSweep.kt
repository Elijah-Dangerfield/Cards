package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.InstallSweepResult
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OrphanInstallSweep
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.StarterInventory
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
 *  2. Per-candidate Kotlin verification:
 *      - **No engagement-grade inventory.** Any owned row that isn't in
 *        [StarterInventory.productIds] and isn't the founding-member
 *        badge counts as engagement — earned via gameplay or purchased
 *        with chips. A candidate with any such row is skipped.
 *      - **No active room seat.** A candidate currently sitting in a
 *        room is held — leaving the L1 sweep would tear their game out
 *        from under them, which is worse than the leaked profile.
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
    private val inventory: InventoryRepository,
    private val rooms: RoomService,
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
            if (!verifyCandidate(candidate)) {
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

    private suspend fun verifyCandidate(candidate: UserId): Boolean {
        val owned = inventory.listOwned(candidate)
        val hasEngagementInventory = owned.any { row ->
            row.productId !in STARTER_AND_FOUNDING_IDS
        }
        if (hasEngagementInventory) return false

        val isInRoom = rooms.snapshot().any { it.memberFor(candidate) != null }
        if (isInRoom) return false

        return true
    }

    companion object {
        private val STARTER_AND_FOUNDING_IDS: Set<String> =
            StarterInventory.productIds.toSet() + FoundingMemberCatalog.PRODUCT_ID
    }
}
