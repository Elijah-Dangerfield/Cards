package com.dangerfield.cards.libraries.networking

/**
 * Opens the on-device network inspector (WiretapKMP) — a debug tool for
 * browsing captured HTTP / WebSocket / SSE traffic with full headers, bodies,
 * status codes, and timing.
 *
 * This is a debug affordance. In release builds the underlying inspector is
 * the zero-overhead noop, so [open] does nothing, and the shake menu only
 * surfaces the entry point when `BuildInfo.isDebug`. Exposed as an interface
 * so feature/app code can offer an "open inspector" action without depending
 * on Wiretap (or any HTTP-client internals) directly.
 */
interface NetworkInspector {
    fun open()
}
