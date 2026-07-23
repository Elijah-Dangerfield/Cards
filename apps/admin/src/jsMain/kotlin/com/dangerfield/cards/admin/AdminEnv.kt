package com.dangerfield.cards.admin

/**
 * The environments this console can manage. URLs only — these are public
 * (the repo is public). Admin tokens are never baked into the bundle: the
 * operator pastes one at runtime and it lives in this browser's localStorage
 * (see [TokenStore]).
 */
internal enum class AdminEnv(
    val displayName: String,
    val baseUrl: String,
) {
    Local("local", "http://localhost:8080"),
    Dev("dev", "https://cards-server-dev.fly.dev"),
    Prod("prod", "https://cards-server-prod.fly.dev"),
}
