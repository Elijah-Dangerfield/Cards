package com.dangerfield.cards.libraries.networking.impl

/**
 * Platform-bound source for the user's preferred language list + region.
 *
 * Implementations read from the OS:
 *  - Android: `LocaleListCompat` / `Locale.getDefault()`.
 *  - iOS: `NSLocale.preferredLanguages` / `NSLocale.currentLocale.countryCode`.
 *
 * Kept as an `expect object` rather than threaded through `BuildInfo` because
 * locale changes at runtime (user switches system language) but build info
 * doesn't. Reading on every call is the right semantic.
 */
expect object LocaleSource {
    /**
     * RFC 5646 `Accept-Language` value composed from the user's preferred
     * language list with descending q-values. Example: `"en-US,en;q=0.9,es;q=0.8"`.
     */
    fun acceptLanguage(): String

    /** ISO 3166-1 alpha-2 country code, or null if the OS doesn't know. */
    fun countryCode(): String?
}
