package com.dangerfield.cards.libraries.networking.impl

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages

actual object LocaleSource {
    actual fun acceptLanguage(): String {
        // NSLocale.preferredLanguages is the ordered list the user set in
        // Settings > Language & Region. Compose it into a q-valued list:
        // first entry q=1.0 (omitted by convention), subsequent decay by 0.1.
        @Suppress("UNCHECKED_CAST")
        val preferred = NSLocale.preferredLanguages as List<String>
        if (preferred.isEmpty()) return "en"
        return preferred
            .take(5)
            .mapIndexed { index, tag ->
                if (index == 0) tag else "$tag;q=${quality(index)}"
            }
            .joinToString(",")
    }

    actual fun countryCode(): String? {
        val locale = NSLocale.currentLocale
        return locale.countryCode
    }

    private fun quality(index: Int): String {
        val q = 1.0 - 0.1 * index
        // Format to one decimal — Foundation has no built-in printf-style,
        // and a simple manual round avoids dragging in regex/locale parsing.
        val truncated = (q * 10).toInt().coerceAtLeast(1)
        return "0.$truncated"
    }
}
