package com.dangerfield.cards.server.domain

/**
 * The curated emoji set every new user picks an avatar from, and the only
 * set we accept as a valid `avatar_emoji` on profile updates.
 *
 * Curation rules:
 *  - No person / face emojis. Random assignment of skin tone or gendered
 *    emoji is a problem we don't need to take on.
 *  - No emojis that depend on ZWJ sequences for rendering (broader font
 *    support, especially on older Android builds).
 *  - Variety across animals / food / nature / objects so the pack feels
 *    playful instead of skewed.
 *
 * Single source of truth: the random-assignment generator
 * ([com.dangerfield.cards.server.data.EmojiAvatarGenerator]) picks from
 * this list, and `PATCH /v1/me` validates against it. Future "premium"
 * avatar packs unlocked via `:libraries:products` are tracked
 * separately — those live in the shop catalog, not here.
 */
object AvatarStarterPack {

    val values: List<String> = listOf(
        // Animals
        "🦊", "🐱", "🐶", "🐯", "🐼", "🐻", "🦁", "🐸", "🐵",
        "🐺", "🦝", "🦓", "🦒", "🦄", "🐹", "🐰", "🐢", "🐙",
        "🦉", "🦅", "🦆", "🐧", "🦦", "🦔", "🐨", "🦘", "🐲",
        // Sea
        "🐳", "🐬", "🦈", "🦑", "🦞", "🦀", "🐡", "🐠", "🐟",
        // Food
        "🍕", "🍔", "🌮", "🍣", "🍩", "🥑", "🍇", "🍓",
        "🥨", "🥐", "🍪", "🍫", "🥕", "🌽", "🍑", "🍒", "🍋",
        // Nature / weather
        "🌵", "🍄", "🌻", "🌸", "🌲", "🌴", "🌊", "🔥", "⚡",
        "🌈", "🌙", "⭐", "🌍",
        // Objects
        "🎲", "🎰", "🎯", "🪙", "🃏", "♠️", "♥️", "♣️", "♦️",
        "🎨", "🎭", "🎺", "🎸", "🥁", "🎤", "🎮", "🚀",
    )

    /** Set membership check for `PATCH /v1/me` validation. O(N) for ~80 entries; fine. */
    fun contains(emoji: String): Boolean = values.contains(emoji)
}
