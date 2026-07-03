package com.dangerfield.cards.features.profile.impl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.month_april
import cards.libraries.resources.generated.resources.month_august
import cards.libraries.resources.generated.resources.month_december
import cards.libraries.resources.generated.resources.month_february
import cards.libraries.resources.generated.resources.month_january
import cards.libraries.resources.generated.resources.month_july
import cards.libraries.resources.generated.resources.month_june
import cards.libraries.resources.generated.resources.month_march
import cards.libraries.resources.generated.resources.month_may
import cards.libraries.resources.generated.resources.month_november
import cards.libraries.resources.generated.resources.month_october
import cards.libraries.resources.generated.resources.month_september
import cards.libraries.resources.generated.resources.month_unknown
import cards.libraries.resources.generated.resources.profile_avatar_edit_a11y
import cards.libraries.resources.generated.resources.profile_boost_confirm_activate
import cards.libraries.resources.generated.resources.profile_boost_confirm_cancel
import cards.libraries.resources.generated.resources.profile_boost_confirm_message
import cards.libraries.resources.generated.resources.profile_boost_confirm_title
import cards.libraries.resources.generated.resources.profile_header_joined
import cards.libraries.resources.generated.resources.profile_items_avatars
import cards.libraries.resources.generated.resources.profile_items_card_back
import cards.libraries.resources.generated.resources.profile_items_emotes
import cards.libraries.resources.generated.resources.profile_items_equipped
import cards.libraries.resources.generated.resources.profile_items_felt
import cards.libraries.resources.generated.resources.profile_item_sheet_locked_a11y
import cards.libraries.resources.generated.resources.profile_items_shop_link
import cards.libraries.resources.generated.resources.profile_items_specialty
import cards.libraries.resources.generated.resources.profile_level_summary_level
import cards.libraries.resources.generated.resources.profile_level_summary_xp_breakdown
import cards.libraries.resources.generated.resources.profile_settings_a11y
import cards.libraries.resources.generated.resources.profile_stats_banner_subtitle_pending
import cards.libraries.resources.generated.resources.profile_stats_banner_subtitle_style_win
import cards.libraries.resources.generated.resources.profile_stats_banner_title
import cards.libraries.resources.generated.resources.profile_achievements_count_see_all
import cards.libraries.resources.generated.resources.profile_achievements_title
import cards.libraries.resources.generated.resources.ui_achievement_medallion_locked_label
import com.dangerfield.cards.features.profile.impl.items.BuyableCosmetic
import com.dangerfield.cards.features.profile.impl.items.CosmeticDetailSheet
import com.dangerfield.cards.features.profile.impl.items.KnownEarnedItems
import com.dangerfield.cards.features.profile.impl.items.LockedCosmeticSheet
import com.dangerfield.cards.features.profile.impl.items.OwnedItem
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.currentProgress
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewBottomBar
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.cutout
import com.dangerfield.cards.libraries.ui.system.LocalLevelCurve
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.PlayStyleRadarMark
import com.dangerfield.cards.libraries.ui.components.toRadarAxes
import com.dangerfield.cards.libraries.ui.components.toStyleCopy
import com.dangerfield.cards.libraries.ui.components.EdgeToEdgeRow
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedalWithDetail
import com.dangerfield.cards.libraries.ui.components.achievement.MedalSize
import com.dangerfield.cards.libraries.ui.components.LevelProgressBar
import com.dangerfield.cards.libraries.ui.components.AccountSetupRetryBanner
import com.dangerfield.cards.libraries.ui.components.SaveProgressBanner
import com.dangerfield.cards.libraries.ui.system.LocalAccountSetupRetry
import com.dangerfield.cards.libraries.ui.components.XpBoostBanner
import com.dangerfield.cards.libraries.ui.components.rememberBoostRemainingMs
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.poker.EmojiBlastOverlay
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.icon.BadgedIconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class ProfileSettings(
    val displayName: String,
    val avatarEmoji: String?,
    val avatarBackgroundColor: String?,
    val rank: Int,
    val xp: Long,
    val isAnonymous: Boolean,
    val gameSpeed: com.dangerfield.cards.libraries.cards.GameSpeed,
    val turnFeedback: com.dangerfield.cards.libraries.cards.TurnFeedback,
    /** Settings-only toggle: false silences in-game achievement reveals. */
    val showAchievementPopups: Boolean = true,
    val appVersion: String,
    val unreadNotificationCount: Int = 0,
    val showQaMenu: Boolean = false,
    val memberSince: kotlin.time.Instant? = null,
    val isFoundingMember: Boolean = false,
)

/**
 * The user's real profile (the Profile tab). Shows identity, level
 * progress, a tappable stats & style banner, a circular achievements preview,
 * and the cosmetics they own grouped by type. App settings live behind the
 * gear in the top-right ([onOpenSettings] → SettingsScreen).
 *
 * Per the screen convention, this composable owns its [Screen] shell; the
 * EntryPoint only wires the repos → params + the navigation callbacks.
 *
 * The banner reads [winRatePercent] and the derived [playStyle] (both from
 * Progression); below the play-style sample gate it shows an honest "play
 * more hands" nudge rather than a fabricated label.
 */
@Composable
fun ProfileScreen(
    settings: ProfileSettings,
    achievementProgress: AchievementProgress,
    ownedItems: List<OwnedItem>,
    winRatePercent: Int?,
    playStyle: PlayStyleAxes? = null,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onTapStats: () -> Unit,
    onSeeAllAchievements: () -> Unit,
    onToggleEquip: (String) -> Unit,
    onOpenShop: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    friendRequests: List<FriendRequestRow> = emptyList(),
    onAcceptFriendRequest: (String) -> Unit = {},
    onDeclineFriendRequest: (String) -> Unit = {},
    boostOwnedCount: Int = 0,
    boostExpiresAtEpochMs: Long? = null,
    onActivateBoost: () -> Unit = {},
    onBuyableTap: (String) -> Unit = {},
    buyableItems: List<BuyableCosmetic> = emptyList(),
    highlightProductId: String? = null,
    onHighlightConsumed: () -> Unit = {},
    // Master gate for the friend-request inbox (SOC-2). Default true so the
    // populated previews render the section; production passes the
    // `social.enabled` flag, which defaults off until V2 flips it on.
    socialEnabled: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
) {
    // Ephemeral "Try it out" emote blast, fired from a pack's detail sheet and
    // rendered over the whole screen — the same animation as the poker table.
    var emojiBlast by remember { mutableStateOf<EmojiBlast?>(null) }

    // Lighting a stashed boost burns 5 minutes starting immediately, so we
    // confirm first (and restate that it's hand XP only) rather than firing on
    // the banner's tap.
    val boostConfirmState = rememberDialogState(initiallyVisible = false)

    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                onNavigateBack = null,
                scrollState = scrollState,
                actions = {
                    // Surface-backed icon button, mirroring the TopBar's
                    // back button treatment. The gear is the only path to the
                    // inbox from here, so it carries the same unread badge the
                    // bottom-tab and the in-Settings row already show.
                    BadgedIconButton(
                        icon = Icons.Settings(stringResource(Res.string.profile_settings_a11y)),
                        onClick = onOpenSettings,
                        badgeCount = settings.unreadNotificationCount,
                        // Nudge in from the screen edge so the badge isn't clipped.
                        modifier = Modifier.padding(end = Dimension.D200),
                    )
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .screenContentPadding(paddingValues = padding)
                    .padding(vertical = 16.dp),
            ) {
                ProfileHeader(settings = settings, onEditProfile = onEditProfile)
                VerticalSpacerD900()

                val accountSetup = LocalAccountSetupRetry.current
                if (accountSetup.pending) {
                    AccountSetupRetryBanner(
                        onRetry = accountSetup.onRetry,
                        isRetrying = accountSetup.isRetrying,
                    )
                    VerticalSpacerD800()
                }

                if (settings.isAnonymous) {
                    SaveProgressBanner(onSignIn = onSignIn)
                    VerticalSpacerD800()
                }

                // Renders only when the user owns a stashed boost or one's
                // running — it self-hides otherwise. The same ticking remaining
                // gates the trailing spacer so it doesn't leave a gap once a
                // lapsed window's timestamp lingers with an empty stash.
                val boostRemainingMs = rememberBoostRemainingMs(boostExpiresAtEpochMs)
                if (boostRemainingMs > 0L || boostOwnedCount > 0) {
                    XpBoostBanner(
                        ownedCount = boostOwnedCount,
                        expiresAtEpochMs = boostExpiresAtEpochMs,
                        onActivate = { boostConfirmState.show() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacerD800()
                }

                StatsStyleBanner(
                    winRatePercent = winRatePercent,
                    playStyle = playStyle,
                    onClick = onTapStats,
                )
                VerticalSpacerD800()

                AchievementsSection(
                    progress = achievementProgress,
                    onSeeAll = onSeeAllAchievements,
                )

                // Friend-requests inbox. Account-bound — a guest has no friend
                // graph, so the section (and its play-to-friend empty state)
                // only shows once the user has claimed an account. Also gated
                // behind SocialEnabled (SOC-2) — hidden entirely when social is
                // descoped, not rendered disabled.
                if (socialEnabled && !settings.isAnonymous) {
                    FriendRequestsSection(
                        requests = friendRequests,
                        onAccept = onAcceptFriendRequest,
                        onDecline = onDeclineFriendRequest,
                    )
                    VerticalSpacerD800()
                }

                OwnedItemsSections(
                    ownedItems = ownedItems,
                    buyableItems = buyableItems,
                    onToggleEquip = onToggleEquip,
                    onOpenShop = onOpenShop,
                    onBuyableTap = onBuyableTap,
                    onTryEmote = { emoji ->
                        emojiBlast = EmojiBlast(
                            emoji = emoji,
                            emittedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        )
                    },
                    highlightProductId = highlightProductId,
                    onHighlightConsumed = onHighlightConsumed,
                )

                BottomBarSpacer()
            }

            // Above the scrolling content (and any dismissed sheet) so a
            // "Try it out" blast reads as a full-screen reaction.
            EmojiBlastOverlay(
                blast = emojiBlast,
                onAnimationComplete = { emojiBlast = null },
                emitterName = settings.displayName,
                emitterEmoji = settings.avatarEmoji,
                emitterColorHex = settings.avatarBackgroundColor,
            )

            Dialog(
                state = boostConfirmState,
                title = stringResource(Res.string.profile_boost_confirm_title),
                description = stringResource(Res.string.profile_boost_confirm_message),
                primaryButtonText = stringResource(Res.string.profile_boost_confirm_activate),
                secondaryButtonText = stringResource(Res.string.profile_boost_confirm_cancel),
                onDismissRequest = { boostConfirmState.dismiss() },
                onPrimaryButtonClicked = {
                    boostConfirmState.dismiss()
                    onActivateBoost()
                },
                onSecondaryButtonClicked = { boostConfirmState.dismiss() },
            )
        }
    }
}

// ---- Header ------------------------------------------------------------

@Composable
private fun ProfileHeader(
    settings: ProfileSettings,
    onEditProfile: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No .clip(CircleShape) on the wrapper — the pencil badge tucks
        // slightly outside the avatar disc and would otherwise be clipped.
        Box(
            modifier = Modifier.clickable(onClick = onEditProfile),
            contentAlignment = Alignment.Center,
        ) {
            AvatarCircle(
                name = settings.displayName,
                // A hair past the D1900 (100dp) token — the largest in the scale.
                size = 108.dp,
                typography = AppTheme.typography.Heading.H1000,
                emoji = settings.avatarEmoji,
                backgroundColorHex = settings.avatarBackgroundColor,
                animationsEnabled = false,
            )
            // The pencil edit affordance — a black pencil on a white disc, cut
            // out of the page background so the ring reads clean (no stray edge).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .cutout(
                        ringColor = AppTheme.colors.background.color,
                        fillColor = AppTheme.colors.surfaceInverse.color,
                        shape = CircleShape,
                        ringWidth = 2.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon = Icons.Pencil(stringResource(Res.string.profile_avatar_edit_a11y)),
                    size = IconSize.Small,
                    color = AppTheme.colors.onSurfaceInverse,
                )
            }
        }
        VerticalSpacerD500()
        Text(
            text = settings.displayName,
            typography = AppTheme.typography.Heading.H900,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
        )
        joinedLine(settings.memberSince)?.let { joined ->
            VerticalSpacerD100()
            Text(
                text = joined,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
        VerticalSpacerD500()
        LevelSummary(
            progress = LocalLevelCurve.current.let { curve ->
                remember(settings.xp, curve) { levelProgressFor(settings.xp, curve) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * "Joined Feb 2026". Null until [memberSince] hydrates — we'd rather show
 * nothing than a placeholder. (The play-style descriptor that used to ride
 * along here was an unwired example value, so it's dropped.)
 */
@Composable
private fun joinedLine(memberSince: kotlin.time.Instant?): String? =
    memberSince?.let { formatJoined(it) }

@Composable
private fun formatJoined(createdAt: kotlin.time.Instant): String {
    val local = createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
    return stringResource(
        Res.string.profile_header_joined,
        stringResource(monthResource(local.monthNumber)),
        local.year,
    )
}

private fun monthResource(monthNumber: Int): StringResource = when (monthNumber) {
    1 -> Res.string.month_january
    2 -> Res.string.month_february
    3 -> Res.string.month_march
    4 -> Res.string.month_april
    5 -> Res.string.month_may
    6 -> Res.string.month_june
    7 -> Res.string.month_july
    8 -> Res.string.month_august
    9 -> Res.string.month_september
    10 -> Res.string.month_october
    11 -> Res.string.month_november
    12 -> Res.string.month_december
    else -> Res.string.month_unknown
}


@Composable
private fun LevelSummary(progress: LevelProgress, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Both labels ride above the bar: the level on the left in the
        // teal accent, the "X / Y XP to Lv N+1" breakdown on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(
                    Res.string.profile_level_summary_level,
                    progress.level,
                ),
                typography = AppTheme.typography.Body.B500.SemiBold,
                color = AppTheme.colors.accentSecondary,
            )
            Text(
                text = stringResource(
                    Res.string.profile_level_summary_xp_breakdown,
                    formatThousands(progress.xpIntoLevel),
                    formatThousands(progress.xpForNextLevel),
                    progress.level + 1,
                ),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
        LevelProgressBar(
            fraction = progress.fraction,
            modifier = Modifier.fillMaxWidth(),
            faceColor = AppTheme.colors.accentSecondary,
            deepColor = AppTheme.colors.accentSecondaryDeep,
        )
    }
}

// ---- Stats & style banner ---------------------------------------------

@Composable
private fun StatsStyleBanner(
    winRatePercent: Int?,
    playStyle: PlayStyleAxes?,
    onClick: () -> Unit,
) {
    // Only show a style name once the user clears the sample gate — below it,
    // the line is an honest "play more hands" nudge, not a fabricated label.
    val derived = playStyle?.takeIf { it.hasEnoughData }
    val style = derived?.let { stringResource(it.toStyleCopy().label) }
    val subtitle = when {
        style != null && winRatePercent != null ->
            stringResource(Res.string.profile_stats_banner_subtitle_style_win, style, winRatePercent)
        style != null -> style
        else -> stringResource(Res.string.profile_stats_banner_subtitle_pending)
    }
    // The play-style name reads in the accent (matching the radar mark); the
    // rest of the line stays muted. Span-on-substring keeps the localized
    // template whole.
    val styleColor = AppTheme.colors.poker.progressionCyan.color
    val subtitleAnnotated = remember(subtitle, style, styleColor) {
        buildAnnotatedString {
            append(subtitle)
            val start = style?.let { subtitle.indexOf(it) } ?: -1
            if (style != null && start >= 0) {
                addStyle(SpanStyle(color = styleColor), start, start + style.length)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.surface,
        contentColor = AppTheme.colors.content,
        radius = Radii.Card,
        onClick = onClick,
        bounceScale = 0.98f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimension.D800),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            // Play-style preview — the same radar mark the Stats page uses,
            // framed in a tile. Shows the real derived shape once available,
            // else the decorative teaser shape.
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surfaceRaised.color),
                contentAlignment = Alignment.Center,
            ) {
                val markAxes = derived?.toRadarAxes()
                if (markAxes != null) {
                    PlayStyleRadarMark(modifier = Modifier.size(42.dp), axes = markAxes)
                } else {
                    PlayStyleRadarMark(modifier = Modifier.size(42.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.profile_stats_banner_title),
                    typography = AppTheme.typography.Body.B600.Bold,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = subtitleAnnotated,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Icon(
                icon = Icons.ChevronRight(""),
                size = IconSize.Small,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

// ---- Achievements ------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsSection(
    progress: AchievementProgress,
    onSeeAll: () -> Unit,
) {
    // Earned achievements (newest first) lead, then locked ones fill the grid so
    // the chase-goals stay visible — greyed out, but recognizable. Non-mystery
    // locked achievements come before mystery ones so the greyed tiles show
    // real icons (mystery stays a "?").
    val display = remember(progress) {
        val earnedList = AllAchievements
            .filter { progress.isEarned(it.id) }
            .sortedByDescending { progress.earned[it.id] ?: 0L }
        val lockedList = AllAchievements
            .filter { !progress.isEarned(it.id) }
            .sortedBy { it.isMystery }
        (earnedList + lockedList).take(AchievementDisplayCount)
    }
    val earnedCount = progress.earned.size
    val total = AllAchievements.size

    // The whole section is one tap target into "See all" — the owner found the
    // header-only hit area too narrow (CARDS-1R). The header keeps its "See all"
    // label as the affordance; the click lives on the wrapping column so a tap on
    // the medal grid navigates too. Individual medals open their own detail sheet
    // (their tap wins inside the grid); the section click is the surrounding area.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeeAll),
    ) {
        SectionHeader(
            title = stringResource(Res.string.profile_achievements_title),
            trailingLabel = stringResource(Res.string.profile_achievements_count_see_all, earnedCount, total),
        )
        VerticalSpacerD200()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D600),
            verticalArrangement = Arrangement.spacedBy(Dimension.D600),
            maxItemsInEachRow = AchievementsPerRow,
        ) {
            display.forEach { achievement ->
                val isEarned = progress.isEarned(achievement.id)
                val isMystery = achievement.isMystery && !isEarned
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AchievementMedalWithDetail(
                        achievement = achievement,
                        earnedAtEpochMs = progress.earned[achievement.id],
                        progress = achievement.currentProgress(progress),
                        size = MedalSize.Small,
                    )
                    VerticalSpacerD100()
                    Text(
                        text = if (isMystery) {
                            stringResource(Res.string.ui_achievement_medallion_locked_label)
                        } else {
                            achievement.name
                        },
                        typography = AppTheme.typography.Label.L300,
                        color = if (isEarned) {
                            AppTheme.colors.content
                        } else {
                            AppTheme.colors.contentTertiary
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            // Pad the final row so medals stay left-aligned in their columns.
            val remainder = display.size % AchievementsPerRow
            if (remainder != 0) {
                repeat(AchievementsPerRow - remainder) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
    VerticalSpacerD800()
}

// Show fewer, larger medals: two rows of three reads bolder than the old
// 4-up grid and keeps the preview focused on the headline achievements.
private const val AchievementDisplayCount = 6
private const val AchievementsPerRow = 3

// ---- Owned items grouped by type --------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OwnedItemsSections(
    ownedItems: List<OwnedItem>,
    buyableItems: List<BuyableCosmetic>,
    onToggleEquip: (String) -> Unit,
    onOpenShop: () -> Unit,
    onBuyableTap: (String) -> Unit,
    onTryEmote: (String) -> Unit,
    highlightProductId: String?,
    onHighlightConsumed: () -> Unit,
) {
    // The "bookshelf": each cosmetic type gets its own shelf, reading like the
    // Achievements grid — owned items first, then dimmed "next to buy" tiles
    // fill out the row (tap → shop). Card backs, felts, avatar packs and emote
    // packs each get a shoppable shelf; badges / titles / tools and other
    // earned-prestige grants collapse into a final owned-only Earned shelf.
    val ownedByShelf = remember(ownedItems) {
        ownedItems.groupBy { shelfFor(it) }
    }
    val buyableByShelf = remember(buyableItems) {
        buyableItems
            .mapNotNull { b -> shoppableShelfFor(b.productId)?.let { it to b } }
            .groupBy({ it.first }, { it.second })
    }

    // Each shelf keeps its own horizontal scroll position. The Profile tab
    // stays composed in the back stack, so without this a row the user
    // scrolled on a prior visit would still be mid-scroll on return. Reset
    // every shelf to its start on each resume so landing on the tab always
    // shows item 0. Fixed-size, stable-order map → safe to remember per shelf.
    val shelfStates = ShelfOrder.associateWith { rememberLazyListState() }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            shelfStates.values.forEach { it.scrollToItem(0) }
        }
    }

    // Tapping any tile opens its detail sheet. We track the selected product
    // id (not the item) so the sheet re-reads fresh equipped state after a
    // toggle re-derives the list — mirrors AchievementMedalWithDetail's
    // per-medal sheet toggle, lifted to one slot for the whole bookshelf.
    var selectedId by remember { mutableStateOf<String?>(null) }

    // A tapped buyable (locked) tile opens a preview sheet rather than jumping
    // straight to the shop, so the user can see what they'd be buying first.
    var lockedSelected by remember { mutableStateOf<BuyableCosmetic?>(null) }

    // Cross-tab "spotlight a just-bought item" — scroll its tile into view and
    // pulse a border. [pulseId] is local so clearing the cache signal (which
    // we do immediately, to avoid a re-pulse on a later cold start) doesn't
    // cut the animation short.
    var pulseId by remember { mutableStateOf<String?>(null) }
    val highlightRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlightProductId) {
        val id = highlightProductId ?: return@LaunchedEffect
        pulseId = id
        onHighlightConsumed()
    }
    // The pulse lifecycle keys off [pulseId], NOT highlightProductId: consuming
    // the highlight above flips highlightProductId to null, which would cancel
    // this coroutine mid-delay and strand the ring on-screen until app restart.
    // Keyed on pulseId, the scroll + fade-out always run to completion.
    LaunchedEffect(pulseId) {
        val id = pulseId ?: return@LaunchedEffect
        // Let the matching tile attach its requester before we scroll to it.
        delay(100)
        Catching { highlightRequester.bringIntoView() }
        delay(HighlightPulseDurationMillis)
        if (pulseId == id) pulseId = null
    }

    ShelfOrder.forEach { shelf ->
        val owned = ownedByShelf[shelf].orEmpty()
        val isShoppable = shelf in ShoppableShelves
        // Once the user owns enough to warrant two rows, the shelf is full on
        // its own — drop the "next to buy" filler. Below that, pad the single
        // row out so even a barely-touched shelf shows "here's what's next."
        val useTwoRows = owned.size >= TwoRowShelfThreshold
        val buyable = if (isShoppable && !useTwoRows) {
            buyableByShelf[shelf].orEmpty()
                .take((ShelfFillTarget - owned.size).coerceAtLeast(0))
        } else {
            emptyList()
        }
        if (owned.isEmpty() && buyable.isEmpty()) return@forEach

        SectionHeader(
            title = stringResource(shelf.labelResource()),
            trailingLabel = if (isShoppable) stringResource(Res.string.profile_items_shop_link) else null,
            onClick = if (isShoppable) onOpenShop else null,
        )
        VerticalSpacerD200()
        // One horizontally-scrolling row by default; column-chunked into two
        // rows once the shelf is well-stocked. Each LazyRow item is a column of
        // up to [rowCount] tiles, so both shapes share one scroll container.
        val tiles: List<ShelfTile> = owned.map(::ShelfTileOwned) + buyable.map(::ShelfTileBuyable)
        val rowCount = if (useTwoRows) 2 else 1
        EdgeToEdgeRow(state = shelfStates.getValue(shelf)) {
            items(tiles.chunked(rowCount), key = { column -> column.first().key }) { column ->
                Column(verticalArrangement = Arrangement.spacedBy(Dimension.D500)) {
                    column.forEach { tile ->
                        when (tile) {
                            is ShelfTileOwned -> {
                                val isPulsing = tile.item.productId == pulseId
                                OwnedCosmeticTile(
                                    item = tile.item,
                                    isPulsing = isPulsing,
                                    onClick = { selectedId = tile.item.productId },
                                    modifier = if (isPulsing) {
                                        Modifier.bringIntoViewRequester(highlightRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                            is ShelfTileBuyable -> {
                                BuyableCosmeticTile(
                                    item = tile.item,
                                    onClick = { lockedSelected = tile.item },
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalSpacerD800()
    }

    selectedId
        ?.let { id -> ownedItems.firstOrNull { it.productId == id } }
        ?.let { item ->
            CosmeticDetailSheet(
                item = item,
                onToggleEquip = onToggleEquip,
                onDismiss = { selectedId = null },
                onTryEmote = onTryEmote,
            )
        }

    lockedSelected?.let { item ->
        LockedCosmeticSheet(
            item = item,
            onOpenInShop = onBuyableTap,
            onDismiss = { lockedSelected = null },
        )
    }
}

private const val HighlightPulseDurationMillis = 1_800L

/** Owned + buyable tiles a single-row shelf grows to before it stops filling. */
private const val ShelfFillTarget = 8

/** Owned-item count at which a shelf splits from one scrolling row into two. */
private const val TwoRowShelfThreshold = 8

/**
 * A tile on a cosmetic shelf — owned (real, tappable, equip-aware) or a dimmed
 * buyable nudge. Unifying both into one list lets the shelf chunk tiles into
 * columns for the one-vs-two-row layout without branching the scroll container.
 */
private sealed interface ShelfTile {
    val key: String
}

private data class ShelfTileOwned(val item: OwnedItem) : ShelfTile {
    override val key: String get() = item.productId
}

private data class ShelfTileBuyable(val item: BuyableCosmetic) : ShelfTile {
    override val key: String get() = "buy_${item.productId}"
}

/**
 * Shelves on the profile bookshelf. Card backs, felts, avatar packs and emote
 * packs each get their own shoppable shelf (owned first, then dimmed buyable
 * tiles). Everything else the player owns — earned/prestige badges, titles,
 * tools — collapses into the owned-only [Earned] shelf.
 */
private enum class Shelf {
    CardBacks,
    Felts,
    Avatars,
    Emotes,
    Earned,
}

private val ShelfOrder = listOf(
    Shelf.CardBacks,
    Shelf.Felts,
    Shelf.Avatars,
    Shelf.Emotes,
    Shelf.Earned,
)

/** Shelves that fill with "next to buy" tiles + show a "Shop ›" link. */
private val ShoppableShelves = setOf(
    Shelf.CardBacks,
    Shelf.Felts,
    Shelf.Avatars,
    Shelf.Emotes,
)

private fun Shelf.labelResource(): StringResource = when (this) {
    Shelf.CardBacks -> Res.string.profile_items_card_back
    Shelf.Felts -> Res.string.profile_items_felt
    Shelf.Avatars -> Res.string.profile_items_avatars
    Shelf.Emotes -> Res.string.profile_items_emotes
    Shelf.Earned -> Res.string.profile_items_specialty
}

/**
 * Shelf for an owned item. Type-first by product-id prefix, so an earned card
 * back still lives with the other card backs rather than getting exiled to the
 * Earned shelf. Only non-slot prestige (badges) and the long tail (titles,
 * tools, anything unrecognized) fall through to [Shelf.Earned].
 */
private fun shelfFor(item: OwnedItem): Shelf =
    shoppableShelfFor(item.productId) ?: Shelf.Earned

/** The shoppable shelf a product id belongs to, or null for the Earned tail. */
private fun shoppableShelfFor(productId: String): Shelf? = when {
    productId.startsWith("cardback_") -> Shelf.CardBacks
    productId.startsWith("felt_") || productId.startsWith("table_") -> Shelf.Felts
    productId.startsWith("avatars_") -> Shelf.Avatars
    productId.startsWith("emotes_") -> Shelf.Emotes
    else -> null
}

/**
 * The product catalog the client syncs (`GET /v1/products`) omits
 * `unlock_only` items, so the founding-member badge arrives with no display
 * metadata (OwnedItem falls back to "🎁"). Reuse the known-earned-item glyphs
 * so the Specialty shelf tile and the detail sheet stay in lockstep. Card
 * backs and felts render their real cosmetic via [CosmeticPreview], so they
 * don't need an entry here.
 */
private val KnownItemEmoji: Map<String, String> =
    KnownEarnedItems.mapValues { it.value.emoji }

private fun displayEmojiFor(item: OwnedItem): String =
    KnownItemEmoji[item.productId] ?: item.iconEmoji

/**
 * A single owned cosmetic on the bookshelf — rendered for real via
 * [CosmeticPreview] (a felt swatch, a face-down card, or its glyph) with an
 * [EquippedBadge] in the corner when it's the active pick in its slot. Tapping
 * opens the [CosmeticDetailSheet].
 */
@Composable
private fun OwnedCosmeticTile(
    item: OwnedItem,
    isPulsing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Accent border fades in then out to spotlight a just-acquired tile
    // without nudging layout. Same treatment the old My Items list used.
    // Equipped tiles read from the corner badge alone — no persistent ring,
    // which the owner found heavy-handed; the pulse is the only thing that
    // ever draws the border now.
    val pulseAlpha by animateFloatAsState(
        targetValue = if (isPulsing) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearEasing),
        label = "OwnedCosmeticTilePulse",
    )
    // Only genuinely equippable cosmetics (card backs / felts / titles) get the
    // equipped badge — packs (emotes, avatars) are owned, not "equipped".
    val showEquippedBadge = item.isEquipped && item.isEquippable
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(Radii.R600.shape)
                .border(2.dp, AppTheme.colors.accentPrimary.color.copy(alpha = pulseAlpha), Radii.R600.shape)
                .clickable(onClick = onClick),
        ) {
            CosmeticPreview(
                productId = item.productId,
                emoji = displayEmojiFor(item),
                size = CosmeticTileSize,
                packEmojis = item.packEmojis,
            )
        }
        if (showEquippedBadge) {
            EquippedBadge(
                // The preview footprint now hugs the card (SHOP-9), so a plain
                // TopEnd badge already lands on the artwork — no trailing inset.
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Cosmetic shelf tile preview edge — a touch larger than the old grid tiles. */
private val CosmeticTileSize = 100.dp

@Composable
private fun EquippedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(CosmeticBadgeSize)
            .cutout(
                ringColor = AppTheme.colors.background.color,
                fillColor = AppTheme.colors.accentPrimary.color,
                shape = CircleShape,
                ringWidth = 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = Icons.Check(stringResource(Res.string.profile_items_equipped)),
            size = IconSize.Smallest,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

/** Corner-badge edge for the equipped / locked markers on a cosmetic tile. */
private val CosmeticBadgeSize = 24.dp

/**
 * A not-yet-owned cosmetic shown after the owned tiles on a shoppable shelf —
 * the real preview, dimmed, so it reads as "available, not yours yet." Tapping
 * opens the shop's purchase sheet for this product. No equipped badge, no
 * price on the tile itself; it's a nudge into the buy flow.
 */
@Composable
private fun BuyableCosmeticTile(item: BuyableCosmetic, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(Radii.R600.shape)
            .clickable(onClick = onClick),
    ) {
        // Only the preview dims — the lock badge stays full-opacity so
        // "you don't own this" reads clearly rather than as a faded smudge.
        Box(modifier = Modifier.alpha(BuyableTileAlpha)) {
            CosmeticPreview(
                productId = item.productId,
                emoji = item.iconEmoji,
                size = CosmeticTileSize,
                packEmojis = item.packEmojis,
            )
        }
        LockedBadge(
            // Preview footprint hugs the card now (SHOP-9); TopEnd sits on the
            // artwork corner without a trailing inset.
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

private const val BuyableTileAlpha = 0.45f

/** Corner badge marking a shelf tile as locked / not-yet-owned. */
@Composable
private fun LockedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(CosmeticBadgeSize)
            .cutout(
                ringColor = AppTheme.colors.background.color,
                fillColor = AppTheme.colors.surfaceHigh.color,
                shape = CircleShape,
                ringWidth = 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = Icons.Lock(stringResource(Res.string.profile_item_sheet_locked_a11y)),
            size = IconSize.Smallest,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

// ---- Previews ----------------------------------------------------------

private fun previewSettings(isAnonymous: Boolean) = ProfileSettings(
    displayName = "QuietAce72",
    avatarEmoji = "🦊",
    avatarBackgroundColor = null,
    rank = if (isAnonymous) 0 else 1200,
    xp = 340,
    isAnonymous = isAnonymous,
    gameSpeed = com.dangerfield.cards.libraries.cards.GameSpeed.Normal,
    turnFeedback = com.dangerfield.cards.libraries.cards.TurnFeedback.Vibrate,
    appVersion = "0.1.0",
    unreadNotificationCount = if (isAnonymous) 0 else 3,
    isFoundingMember = !isAnonymous,
)

@org.jetbrains.compose.ui.tooling.preview.Preview(heightDp = 2000)
@Composable
private fun ProfileScreenPreview() {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = previewSettings(isAnonymous = false),
            achievementProgress = AchievementProgress(
                earned = mapOf(
                    AllAchievements[0].id to now,
                    AllAchievements[1].id to now,
                    AllAchievements[2].id to now,
                ),
                counters = emptyMap(),
                customCounters = emptyMap(),
            ),
            ownedItems = listOf(
                OwnedItem(
                    productId = "cardback_default",
                    title = "Classic",
                    subtitle = "",
                    description = null,
                    iconEmoji = "🂠",
                    isEquipped = true,
                    isEquippable = true,
                ),
                OwnedItem(
                    productId = "felt_default",
                    title = "Default felt",
                    subtitle = "",
                    description = null,
                    iconEmoji = "🟩",
                    isEquipped = true,
                    isEquippable = true,
                ),
                OwnedItem(
                    productId = "avatars_starter",
                    title = "Starter pack",
                    subtitle = "",
                    description = null,
                    iconEmoji = "🦊",
                    isEquipped = false,
                    isEquippable = false,
                    packEmojis = listOf("🦊", "🐻", "🐰", "🐨"),
                ),
                OwnedItem(
                    productId = "emotes_baller",
                    title = "Baller pack",
                    subtitle = "",
                    description = null,
                    iconEmoji = "💸",
                    isEquipped = false,
                    isEquippable = false,
                    packEmojis = listOf("💸", "💎", "🤑", "📈"),
                ),
                OwnedItem(
                    productId = "badge_founding_member_1000",
                    title = "Founding member",
                    subtitle = "",
                    description = null,
                    iconEmoji = "🏛",
                    isEquipped = true,
                    isEquippable = true,
                    acquisitionSource = AcquisitionSource.Earned,
                ),
            ),
            buyableItems = listOf(
                BuyableCosmetic(productId = "cardback_neon", title = "Neon", iconEmoji = "🃏"),
                BuyableCosmetic(productId = "cardback_galaxy", title = "Galaxy", iconEmoji = "✨"),
                BuyableCosmetic(productId = "felt_royal_red", title = "Royal Red", iconEmoji = "🟥"),
                BuyableCosmetic(
                    productId = "emotes_royal",
                    title = "Royal emotes",
                    iconEmoji = "👑",
                    packEmojis = listOf("👑", "🃏", "♠️", "♥️"),
                ),
            ),
            winRatePercent = 58,
            friendRequests = listOf(
                FriendRequestRow("u1", "Jordan", "🦊", "#E48A58"),
                FriendRequestRow("u2", "Priya", "💀", "#9E9E9E"),
            ),
            onOpenSettings = {},
            onEditProfile = {},
            onTapStats = {},
            onSeeAllAchievements = {},
            onToggleEquip = {},
            onOpenShop = {},
            onSignIn = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ProfileScreenPreview_FreshUser() {
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = previewSettings(isAnonymous = true),
            achievementProgress = AchievementProgress.Empty,
            ownedItems = emptyList(),
            winRatePercent = null,
            onOpenSettings = {},
            onEditProfile = {},
            onTapStats = {},
            onSeeAllAchievements = {},
            onToggleEquip = {},
            onOpenShop = {},
            onSignIn = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ProfileScreenPreview_StockedShelf() {
    // A well-stocked card-backs shelf (9 owned) exercises the two-row split and
    // the equipped ring; other shelves stay single-row with buyable fill.
    PreviewContent(bottomBar = PreviewBottomBar.Profile) {
        ProfileScreen(
            settings = previewSettings(isAnonymous = false),
            achievementProgress = AchievementProgress.Empty,
            ownedItems = List(9) { i ->
                OwnedItem(
                    productId = "cardback_$i",
                    title = "Card back $i",
                    subtitle = "",
                    description = null,
                    iconEmoji = "🂠",
                    isEquipped = i == 0,
                    isEquippable = true,
                )
            },
            buyableItems = listOf(
                BuyableCosmetic(productId = "felt_royal_red", title = "Royal Red", iconEmoji = "🟥"),
            ),
            winRatePercent = 58,
            onOpenSettings = {},
            onEditProfile = {},
            onTapStats = {},
            onSeeAllAchievements = {},
            onToggleEquip = {},
            onOpenShop = {},
            onSignIn = {},
        )
    }
}
