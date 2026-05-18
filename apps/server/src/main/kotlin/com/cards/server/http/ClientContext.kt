package com.dangerfield.cards.server.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.request.header

/**
 * The set of client metadata every API call carries.
 *
 * Built from request headers by [ApplicationCall.clientContext]. Endpoints take
 * this as a single object instead of poking at headers directly — adding a new
 * client signal (say, app variant, A/B bucket) means updating one parser and
 * every endpoint that wants it picks it up automatically.
 *
 * Defaults are chosen to keep the server functional when an old client doesn't
 * send a header: an unknown platform is treated as "other" and gets the
 * platform-agnostic subset of the catalog; missing locale falls back to
 * English; missing country is just `null`.
 *
 * Header conventions documented client-side in
 * [com.dangerfield.cards.libraries.networking.ClientHeaders].
 */
data class ClientContext(
    val platform: Platform,
    val appVersion: String?,
    val buildNumber: Int?,
    /** Ordered list of preferred locales — first is the user's top pick. */
    val preferredLocales: List<String>,
    /** ISO 3166-1 alpha-2 if the client sent it, else null. */
    val countryCode: String?,
) {
    enum class Platform { Android, iOS, Other }

    companion object {
        const val HEADER_PLATFORM: String = "X-Platform"
        const val HEADER_APP_VERSION: String = "X-App-Version"
        const val HEADER_BUILD_NUMBER: String = "X-Build-Number"
        const val HEADER_COUNTRY_CODE: String = "X-Country-Code"
    }
}

/** Parse the calling request's headers into a [ClientContext]. */
fun ApplicationCall.clientContext(): ClientContext {
    val platform = when (request.header(ClientContext.HEADER_PLATFORM)?.lowercase()) {
        "ios" -> ClientContext.Platform.iOS
        "android" -> ClientContext.Platform.Android
        else -> ClientContext.Platform.Other
    }
    // `Accept-Language` ranked items: ktor parses q-values and returns them
    // pre-sorted by quality descending. We strip down to just the language
    // tags (the catalog matcher does its own RFC 4647 lookup).
    val locales = request.acceptLanguageItems()
        .map { it.value }
        .ifEmpty { listOf("en") }
    val country = request.header(ClientContext.HEADER_COUNTRY_CODE)
        ?.takeIf { it.length == 2 }
        ?.uppercase()
    return ClientContext(
        platform = platform,
        appVersion = request.header(ClientContext.HEADER_APP_VERSION),
        buildNumber = request.header(ClientContext.HEADER_BUILD_NUMBER)?.toIntOrNull(),
        preferredLocales = locales,
        countryCode = country,
    )
}
