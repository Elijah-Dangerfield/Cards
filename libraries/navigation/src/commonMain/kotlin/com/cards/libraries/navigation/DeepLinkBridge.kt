@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.navigation

import kotlinx.coroutines.flow.SharedFlow
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Pipe for incoming deep-link URLs from the host platform into Compose
 * Navigation. Each platform pushes URLs in via [emit]; the app collects
 * [urls] and routes them with `NavController.handleDeepLink`.
 *
 * Why a bridge instead of direct calls: the platform entry points (iOS
 * `.onOpenURL`, Android `onNewIntent`) fire outside Compose. The bridge
 * decouples them from the NavController, which only exists for the lifetime
 * of the App composable.
 *
 * Android note: Compose NavHost reads `Activity.intent.data` automatically
 * when the Activity has the right intent filter, so emitting on Android is
 * usually unnecessary. iOS has no equivalent — `.onOpenURL` must forward
 * here.
 *
 * Per-route registration uses the `deepLinks` parameter on `screen<Route>`.
 * Always build the link with `routeDeepLink<Route>` (not the bare
 * `navDeepLink`) — every [Route] carries `AnimationType` args, and the raw
 * builder's empty typeMap crashes at graph-build time:
 *
 * ```
 * screen<ProfileRoute>(
 *     deepLinks = listOf(routeDeepLink<ProfileRoute>(basePath = "https://example.com/profile"))
 * ) { ... }
 * ```
 */
@ObjCName("DeepLinkBridge", exact = true)
interface DeepLinkBridge {
    val urls: SharedFlow<String>
    fun emit(url: String)
}
