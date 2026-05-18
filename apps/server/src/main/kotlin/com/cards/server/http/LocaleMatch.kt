package com.dangerfield.cards.server.http

/**
 * Pick the best string from a [Map<String, String>] keyed by BCP 47 language
 * tag, given the client's preferred locale priority list.
 *
 * Algorithm (simplified RFC 4647 "lookup"):
 *  1. For each preferred locale in priority order…
 *     a. If the catalog has the exact tag, return it.
 *     b. Otherwise strip subtag suffixes (`en-US` → `en`) and retry.
 *  2. If no preference matches, fall back to `"en"`, then to the first value
 *     in the map, then to [default].
 *
 * Case-insensitive throughout — the catalog typically stores `"en"` lowercase,
 * but `Accept-Language` may carry `"EN-US"`.
 *
 * Why not pull in icu4j or similar: the catalog locale set is tiny (a
 * handful of tags), the lookup is O(preferences × tagDepth), and avoiding a
 * heavy dependency keeps the server image small. If we localize into 20+
 * languages later, swap this for a real matcher.
 */
internal fun pickLocalized(
    byLocale: Map<String, String>,
    preferences: List<String>,
    default: String = "",
): String {
    if (byLocale.isEmpty()) return default
    val normalized = byLocale.mapKeys { (k, _) -> k.lowercase() }

    for (raw in preferences) {
        val tag = raw.lowercase()
        normalized[tag]?.let { return it }
        // Walk parent: en-US-x-foo → en-US → en
        var parent = tag
        while (parent.contains('-')) {
            parent = parent.substringBeforeLast('-')
            normalized[parent]?.let { return it }
        }
    }

    return normalized["en"]
        ?: normalized.values.firstOrNull()
        ?: default
}
