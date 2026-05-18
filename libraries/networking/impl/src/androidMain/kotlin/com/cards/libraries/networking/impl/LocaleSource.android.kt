package com.dangerfield.cards.libraries.networking.impl

import java.util.Locale

actual object LocaleSource {
    actual fun acceptLanguage(): String {
        // Android's `Locale.getDefault()` gives the top preferred locale.
        // LocaleListCompat (androidx.core) gives the full list, but adding the
        // dep just for this is overkill — most apps care about #1 and provide
        // it with a `q=0.9` fallback to the language-only variant.
        val primary = Locale.getDefault()
        val tag = primary.toLanguageTag()
        val language = primary.language
        return if (language.isNotEmpty() && tag != language) {
            "$tag,$language;q=0.9"
        } else {
            tag.ifEmpty { "en" }
        }
    }

    actual fun countryCode(): String? {
        val country = Locale.getDefault().country
        return country.ifEmpty { null }
    }
}
