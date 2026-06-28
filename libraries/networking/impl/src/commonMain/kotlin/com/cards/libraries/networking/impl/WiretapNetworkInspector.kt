package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.networking.NetworkInspector
import io.ktor.client.HttpClientConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * [NetworkInspector] backed by WiretapKMP's programmatic launcher. The
 * launcher comes transitively with the Wiretap Ktor artifact, so capture
 * (the installed plugin) and launch share the same dependency + variant
 * swap.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WiretapNetworkInspector : NetworkInspector {
    override fun open() {
        // The launcher is already the noop in release builds, but guard on
        // isDebug anyway: an iOS release framework built with
        // `cards.wiretap.ios` left on would link the real launcher, and this
        // keeps the inspector from ever opening there.
        if (BuildInfo.isDebug) launchNetworkInspector()
    }
}

/**
 * Opens the Wiretap console. Per-platform because the library's
 * `launchWiretapConsole()` presents differently:
 *
 *  - **Android** uses it as-is — a full-screen Activity whose root maps the
 *    system back gesture to `finish()`, so there's always a way out.
 *  - **iOS** does NOT use it: that path presents a `UIModalPresentationFullScreen`
 *    modal, and the console's root screen has no close button and a full-screen
 *    modal has no swipe-to-dismiss — so you get trapped. The iOS actual instead
 *    presents the console as a page sheet (swipe down to dismiss).
 */
internal expect fun launchNetworkInspector()

/**
 * Installs the Wiretap capture plugin on an [HttpClient] (callers gate this on
 * `BuildInfo.isDebug`). Per-platform because Wiretap can't run everywhere it
 * compiles: on Android the plugin's request path resolves dependencies from
 * Wiretap's own DI, which is bootstrapped by an `androidx.startup` initializer
 * that only runs in a real app process — NOT in host-JVM unit tests (e.g. the
 * `:apps:integration` harness, which runs the real client as `testDebugUnitTest`
 * with `isDebug == true`). Installing it there makes every request crash with
 * `Could not create instance for ...`, so the Android actual skips installation
 * when running under a unit test.
 */
internal expect fun HttpClientConfig<*>.installNetworkInspector()

/**
 * Installs Wiretap's WebSocket-capture plugin on a WS-capable [HttpClient] —
 * the gameplay room socket flows through the authenticated client, and the
 * hardest multiplayer bugs live in those frames (ENG-8). The plugin wraps every
 * WebSocket session opened through the client transparently (no per-frame hook),
 * logging the connection, every sent/received frame, and close/error events
 * alongside the HTTP traffic in the same inspector.
 *
 * Gated identically to [installNetworkInspector]: callers wrap it in
 * `BuildInfo.isDebug`, the Android actual additionally skips host-JVM unit tests
 * (Wiretap's DI isn't bootstrapped there), and the iOS noop/real split is driven
 * by `cards.wiretap.ios` at the dependency level. Must be installed *after*
 * Ktor's `WebSockets` plugin so it can wrap the raw session before `WebSockets`
 * transforms it.
 */
internal expect fun HttpClientConfig<*>.installWebSocketInspector()
