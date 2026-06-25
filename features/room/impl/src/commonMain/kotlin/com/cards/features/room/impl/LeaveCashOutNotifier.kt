package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import me.tatarka.inject.annotations.Inject
import org.jetbrains.compose.resources.getString
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_leave_cashout_credited
import kotlin.time.Duration.Companion.milliseconds

/**
 * Confirms a leave-time wallet credit on the surface the player lands on.
 *
 * Pulled out of [PlayPokerViewModel] because the message is composed via
 * `getString` (a suspend resource read that only resolves at runtime) and
 * fired through the global, host-agnostic [showSnackBar] — neither of which
 * is exercisable from a JVM unit test. The VM holds the testable decision
 * (compute the credited delta, decide whether to confirm); this seam owns
 * the un-unit-testable rendering, so a test fake records the call instead.
 */
interface LeaveCashOutNotifier {
    suspend fun confirmCredit(credited: Long, balanceAfter: Long)
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class SnackbarLeaveCashOutNotifier : LeaveCashOutNotifier {

    private val logger = KLog.withTag("LeaveCashOutNotifier")

    override suspend fun confirmCredit(credited: Long, balanceAfter: Long) {
        Catching {
            showSnackBar(
                message = getString(
                    Res.string.room_leave_cashout_credited,
                    formatThousands(credited),
                    formatThousands(balanceAfter),
                ),
                emoji = "🪙",
                // The leave teardown pops this screen and routes to Home; the
                // toast waits out that transition so it lands on Home's host
                // rather than racing the dying play-screen host.
                delayBy = TOAST_DELAY,
            )
        }.onFailure { e -> logger.w(e) { "leave cash-out toast failed" } }
    }

    private companion object {
        val TOAST_DELAY = 600.milliseconds
    }
}
