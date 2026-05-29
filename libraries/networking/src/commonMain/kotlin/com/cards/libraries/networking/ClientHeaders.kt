package com.dangerfield.cards.libraries.networking

/**
 * Per-request metadata the client attaches to every API call.
 *
 * Centralizing these means feature code never deals with header strings — the
 * networking layer's [io.ktor.client.HttpClient] is configured once to read
 * from [ClientHeadersProvider] and add them on every request. New endpoints
 * inherit the same context for free.
 *
 * Industry conventions in use:
 *  - [acceptLanguage] follows RFC 5646 + the standard `Accept-Language` quality
 *    priority list (`en-US,en;q=0.9`).
 *  - [countryCode] is ISO 3166-1 alpha-2 (`US`, `GB`, `JP`).
 *  - The `X-Platform`, `X-App-Version`, `X-Build-Number`, `X-Country-Code`
 *    custom headers are namespaced to avoid collision with framework headers.
 */
data class ClientHeaders(
    /** `ios` or `android` — lowercase, stable across versions. */
    val platform: String,
    /** Semver string like `1.2.3`. */
    val appVersion: String,
    /** Monotonic build number (CI counter). */
    val buildNumber: String,
    /** `Accept-Language` value: RFC 5646 with q-values, e.g. `en-US,en;q=0.9`. */
    val acceptLanguage: String,
    /** ISO 3166-1 alpha-2, or `null` if unknown — server may geo-IP fallback. */
    val countryCode: String?,
    /**
     * Canonical UUID string for this app install, or `null` if the
     * install-id provider hasn't hydrated yet on this boot. See
     * [InstallIdProvider].
     */
    val installId: String?,
) {
    companion object {
        const val HEADER_PLATFORM: String = "X-Platform"
        const val HEADER_APP_VERSION: String = "X-App-Version"
        const val HEADER_BUILD_NUMBER: String = "X-Build-Number"
        const val HEADER_COUNTRY_CODE: String = "X-Country-Code"
        const val HEADER_INSTALL_ID: String = "X-Install-Id"
        // Accept-Language is a standard header — use io.ktor.http.HttpHeaders.AcceptLanguage.
    }
}

/**
 * Supplies the [ClientHeaders] block injected into every outgoing request.
 *
 * Default impl in [com.dangerfield.cards.libraries.networking.impl] sources
 * platform + build info from `BuildInfo` and locale/country from the OS via
 * `expect`/`actual` platform binding. Tests can bind a fake.
 *
 * Implementations MUST be cheap and non-suspending — they're called on every
 * outgoing request. Cache aggressively if needed.
 */
interface ClientHeadersProvider {
    fun current(): ClientHeaders
}
