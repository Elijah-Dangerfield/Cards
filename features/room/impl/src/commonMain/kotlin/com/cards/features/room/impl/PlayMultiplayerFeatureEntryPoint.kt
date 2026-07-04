package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.RemotePokerSessionFactory
import com.dangerfield.cards.features.room.impl.ui.PlayPokerScreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.profile.ClaimAccountRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.features.room.RoomKind
import com.dangerfield.cards.features.rooms.PublicFindRoute
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.serializableType
import com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarLevel
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_incompatible_version
import cards.libraries.resources.generated.resources.room_intent_rejected
import cards.libraries.resources.generated.resources.room_intent_timed_out
import cards.libraries.resources.generated.resources.room_next_hand_resyncing
import cards.libraries.resources.generated.resources.room_next_hand_unavailable
import cards.libraries.resources.generated.resources.room_opponent_left
import cards.libraries.resources.generated.resources.room_quick_buy_failed
import cards.libraries.resources.generated.resources.room_quick_buy_store_unavailable
import cards.libraries.resources.generated.resources.room_quick_buy_success
import org.jetbrains.compose.resources.getString
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.reflect.typeOf

/**
 * Sibling to [PlayPokerFeatureEntryPoint] — same VM + screen pair,
 * but constructs a [RemotePokerSessionFactory] from the route's
 * `roomCode` instead of the bot-mode solo factory.
 *
 * The local user's id is needed to pick out their seat from each
 * `GameState` (the factory's `tableFor` projection keys off it).
 * Resolution is async — we read it once via [LaunchedEffect] and
 * render a small loading indicator until [AuthState.Authenticated]
 * lands. The cached lookup is fast on the warm path; the spinner is
 * only visible on cold starts where the auth bootstrap hasn't
 * settled yet.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class PlayMultiplayerFeatureEntryPoint(
    private val remoteFactoryFactory: (roomCode: String, localUserId: String, isPublicTable: Boolean) -> RemotePokerSessionFactory,
    private val playPokerVmFactory: (sessionFactory: PokerSessionFactory) -> PlayPokerViewModel,
    private val authRepository: AuthRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        // RoomKind is a non-primitive (enum) route arg; iOS/Native has no
        // reflection fallback, so it needs an explicit NavType in the typeMap
        // or graph-build throws "could not find any NavType for argument kind".
        screen<PlayMultiplayerRoute>(
            typeMap = mapOf(typeOf<RoomKind>() to serializableType<RoomKind>()),
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<PlayMultiplayerRoute>()
            // Multiplayer needs a real (server) account. If the session resolves
            // Unauthenticated — e.g. a guest whose account creation is still
            // pending (degraded) — bounce back instead of spinning forever.
            val localUserId = rememberLocalUserId(onUnauthenticated = { router.goBack() })
            if (localUserId == null) {
                LoadingPlaceholder()
                return@screen
            }
            val viewModel: PlayPokerViewModel = viewModel(
                key = "play-mp-${route.roomCode}",
            ) {
                val factory = remoteFactoryFactory(
                    route.roomCode,
                    localUserId,
                    route.kind == RoomKind.Public,
                )
                playPokerVmFactory(factory)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            // One-shot events. RoomClosed pops (the room is gone). OpponentsLeft
            // tears down this screen and routes by room kind — back to the lobby
            // for a private game (where the lone player can re-invite), or to
            // matchmaking search for a public one. The bust-upsell events stay
            // in-game: QuickBuyFinished toasts the result, RebuyInsufficientChips
            // opens the quick-buy sheet, ClaimAccountRequired routes an anonymous
            // user to the same account-claim flow the shop uses.
            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is PlayPokerEvent.RoomClosed -> {
                            // A frame the client couldn't parse closes the room as
                            // incompatible (ENG-7). Surface the "update may help"
                            // message before exiting so the player understands the
                            // dead table rather than getting a silent pop.
                            if (event.reason ==
                                com.dangerfield.cards.libraries.rooms.ClosedReason.IncompatibleVersion
                            ) {
                                showSnackBar(
                                    message = getString(Res.string.room_incompatible_version),
                                    level = SnackbarLevel.Error,
                                )
                            }
                            router.goBack()
                        }
                        PlayPokerEvent.OpponentsLeft -> router.batch {
                            when (route.kind) {
                                // Pop back to the player's EXISTING lobby (it sits
                                // below the play screen for both host and joiner
                                // paths — autoCreate=true vs prefilledCode=… —
                                // hence the class-based pop). The lobby VM is
                                // still alive, still subscribed to the room, so
                                // the lone player picks back up where they were
                                // and can wait / re-invite without a duplicate
                                // lobby stacking on top auto-rejoining a room
                                // they never left.
                                RoomKind.Private -> popBackTo(LobbyRoute::class, inclusive = false)
                                // Public games are anonymous + stakes-flexible, so send
                                // them back to Find to re-pick a range and search again
                                // (the old table's buy-in range isn't carried here).
                                RoomKind.Public -> {
                                    goBack()
                                    navigate(PublicFindRoute())
                                }
                            }
                        }
                        is PlayPokerEvent.OpponentLeft -> showSnackBar(
                            message = getString(Res.string.room_opponent_left, event.displayName),
                            emoji = "👋",
                        )
                        is PlayPokerEvent.QuickBuyFinished -> showQuickBuySnackbar(event.outcome)
                        PlayPokerEvent.ClaimAccountRequired -> router.navigate(ClaimAccountRoute())
                        PlayPokerEvent.RebuyInsufficientChips ->
                            viewModel.takeAction(PlayPokerAction.OpenQuickBuy)
                        PlayPokerEvent.NextHandUnavailable -> showSnackBar(
                            message = getString(Res.string.room_next_hand_unavailable),
                            emoji = "⏳",
                        )
                        PlayPokerEvent.NextHandResyncing -> showSnackBar(
                            message = getString(Res.string.room_next_hand_resyncing),
                            emoji = "🔄",
                        )
                        is PlayPokerEvent.IntentFeedback -> when (event.kind) {
                            IntentFeedbackKind.TimedOut -> showSnackBar(
                                message = getString(Res.string.room_intent_timed_out),
                                level = SnackbarLevel.Error,
                            )
                            IntentFeedbackKind.Rejected -> showSnackBar(
                                message = getString(Res.string.room_intent_rejected),
                                level = SnackbarLevel.Error,
                            )
                        }
                        else -> Unit
                    }
                }
            }
            PlayPokerScreen(
                state = state,
                onAction = viewModel::takeAction,
                // Leaving an in-progress MP game lands on Home, never the dead
                // setup screen the player came through. A private game pushes
                // PlayMultiplayer on top of its Lobby, so popping a single
                // screen would strand the player back in the lobby (CARDS-1Y) —
                // pop the whole chain past the lobby to the Home tab root
                // instead. The class-based pop is load-bearing: the host's
                // lobby is LobbyRoute(autoCreate=true), the joiner's is
                // LobbyRoute(prefilledCode=…), and route-instance equality
                // would silently no-op on the mismatched args, stranding the
                // player on the dead play screen. A public game has no lobby
                // underneath, so its plain goBack + switchTab already surfaces
                // Home. Either way the pop tears down this VM so the socket
                // closes and the server frees the seat after grace; batching
                // keeps a mid-teardown scope death from stranding the player
                // on a dead table.
                onBack = {
                    // Fire the leave teardown here too, not only from the
                    // screen's BackHandler / top-arrow. An iOS edge-swipe pop can
                    // reach this lambda without going through Compose's
                    // BackHandler, which would pop the screen with the wallet
                    // un-reconciled — the settled balance then stays invisible
                    // until the next foreground (MP-23 / CARDS-5B). The VM
                    // latches the teardown, so this is idempotent with the
                    // screen's own LeaveTable on the normal back paths.
                    viewModel.takeAction(PlayPokerAction.LeaveTable)
                    router.batch {
                        when (route.kind) {
                            RoomKind.Private -> popBackTo(LobbyRoute::class, inclusive = true)
                            RoomKind.Public -> {
                                goBack()
                                switchTab(HomeRoute())
                            }
                        }
                    }
                },
                onTapXp = { router.navigate(StatsRoute()) },
            )
        }
    }

    @Composable
    private fun rememberLocalUserId(onUnauthenticated: () -> Unit): String? {
        var userId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            // current() suspends until auth resolves to a definitive state, so
            // an Unauthenticated result here is final — not "still loading."
            when (val current = authRepository.current()) {
                is AuthState.Authenticated -> userId = current.userId
                is AuthState.Unauthenticated -> onUnauthenticated()
            }
        }
        return userId
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Toast the result of an in-game quick-buy, mirroring the shop's
 * PurchaseFinished feedback. Cancellation is silent; ClaimAccountRequired is
 * routed (not toasted) by the caller, so it's a no-op here.
 */
private suspend fun showQuickBuySnackbar(outcome: IapPurchaseOutcome) {
    when (outcome) {
        is IapPurchaseOutcome.Success -> showSnackBar(
            message = getString(Res.string.room_quick_buy_success, formatThousands(outcome.grantedChips)),
            emoji = "🪙",
        )
        is IapPurchaseOutcome.AlreadyOwned -> showSnackBar(
            message = getString(Res.string.room_quick_buy_success, formatThousands(outcome.grantedChips)),
            emoji = "🪙",
        )
        IapPurchaseOutcome.StoreUnavailable -> showSnackBar(
            message = getString(Res.string.room_quick_buy_store_unavailable),
            level = SnackbarLevel.Error,
        )
        is IapPurchaseOutcome.Failed,
        IapPurchaseOutcome.NotSignedIn,
        -> showSnackBar(
            message = getString(Res.string.room_quick_buy_failed),
            level = SnackbarLevel.Error,
        )
        IapPurchaseOutcome.Cancelled,
        IapPurchaseOutcome.ClaimAccountRequired,
        -> Unit
    }
}
