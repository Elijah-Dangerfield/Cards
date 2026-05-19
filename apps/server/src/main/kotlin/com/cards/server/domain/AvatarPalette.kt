package com.dangerfield.cards.server.domain

/**
 * Curated palette the avatar-background picker can choose from.
 *
 * Closed set so we don't end up with users' avatars rendered against
 * arbitrary user-supplied colors (palette drift, accessibility checks,
 * dark/light mode contrast). The client picker reads this same list via
 * `GET /v1/avatars` so adding a swatch is server-only.
 *
 * Hex strings normalized to lowercase. NULL on the profile row =
 * "use the theme default" (handled client-side).
 */
object AvatarPalette {

    val values: List<String> = listOf(
        "#5bc79b", // Aurora — matches accentPrimary
        "#7555ff", // Pulse — matches accentSecondary
        "#ff6b35", // Sunset
        "#ffc857", // Honey
        "#52a2ff", // Sky
        "#ff5da2", // Coral
        "#a18bff", // Lavender
        "#37d5c2", // Mint
    )

    fun isValid(hex: String): Boolean = hex.lowercase() in values
}
