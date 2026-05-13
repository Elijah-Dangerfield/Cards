package com.dangerfield.cards.libraries.networking

/**
 * Per-project network configuration. Bind a project-specific implementation in
 * your app's `impl` module to set the API base URL and default request timeout.
 *
 * The template's default impl reads from BuildConfig if you wire one up;
 * otherwise it's a placeholder pointing at example.com.
 */
interface NetworkConfig {
    /** Base URL applied to every request via Ktor's `defaultRequest` plugin. */
    val baseUrl: String

    /** Per-request timeout in milliseconds. Default 30s. */
    val requestTimeoutMillis: Long get() = 30_000L
}
